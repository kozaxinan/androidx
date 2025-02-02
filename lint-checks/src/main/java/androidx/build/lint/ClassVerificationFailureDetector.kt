/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UnstableApiUsage")

package androidx.build.lint

import com.android.SdkConstants.ATTR_VALUE
import com.android.SdkConstants.DOT_JAVA
import com.android.tools.lint.checks.ApiDetector.Companion.REQUIRES_API_ANNOTATION
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.checks.ApiLookup
import com.android.tools.lint.checks.ApiLookup.equivalentName
import com.android.tools.lint.checks.ApiLookup.startsWithEquivalentPrefix
import com.android.tools.lint.checks.VersionChecks.Companion.codeNameToApi
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Desugaring
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.UastLintUtils.Companion.getLongAttribute
import com.android.tools.lint.detector.api.getInternalMethodName
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiSuperExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UInstanceExpression
import org.jetbrains.uast.USuperExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.java.JavaUAnnotation
import org.jetbrains.uast.java.JavaUSimpleNameReferenceExpression
import org.jetbrains.uast.util.isConstructorCall
import org.jetbrains.uast.util.isMethodCall

/**
 * This check detects references to platform APIs that are likely to cause class verification
 * failures.
 * <p>
 * Specifically, this check looks for references to APIs that were added prior to the library's
 * minSdkVersion and therefore may not exist on the run-time classpath. If the class verifier
 * detects such a reference, e.g. while verifying a class containing the reference, it will abort
 * verification. This will prevent the class from being optimized, resulting in potentially severe
 * performance losses.
 * <p>
 * See Chromium's excellent guide to Class Verification Failures for more information:
 * https://chromium.googlesource.com/chromium/src/+/HEAD/build/android/docs/class_verification_failures.md
 */
class ClassVerificationFailureDetector : Detector(), SourceCodeScanner {
    private var apiDatabase: ApiLookup? = null

    override fun beforeCheckEachProject(context: Context) {
        apiDatabase = ApiLookup.get(context.client, context.project.buildTarget)
    }

    override fun afterCheckEachProject(context: Context) {
        apiDatabase = null
    }

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (apiDatabase == null) {
            return null
        }
        return ApiVisitor(context)
    }

    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java)

    // Consider making this a top class and pass in apiDatabase explicitly.
    private inner class ApiVisitor(private val context: JavaContext) : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve()
            if (method != null) {
                visitCall(method, node, node)
            }
        }

        private fun visitCall(
            method: PsiMethod,
            call: UCallExpression?,
            reference: UElement
        ) {
            if (call == null) {
                return
            }
            val apiDatabase = apiDatabase ?: return
            val containingClass = method.containingClass ?: return
            val evaluator = context.evaluator
            val owner = evaluator.getQualifiedName(containingClass)
                ?: return // Couldn't resolve type
            if (!apiDatabase.containsClass(owner)) {
                return
            }
            val name = getInternalMethodName(method)
            val desc = evaluator.getMethodDescription(
                method,
                false,
                false
            ) // Couldn't compute description of method for some reason; probably
                // failure to resolve parameter types
                ?: return
            var api = apiDatabase.getMethodVersion(owner, name, desc)
            if (api == NO_API_REQUIREMENT) {
                return
            }
            if (api <= context.project.minSdk) {
                return
            }
            if (call.isMethodCall()) {
                val qualifier = call.receiver
                if (qualifier != null &&
                    qualifier !is UThisExpression &&
                    qualifier !is PsiSuperExpression
                ) {
                    val receiverType = qualifier.getExpressionType()
                    if (receiverType is PsiClassType) {
                        val containingType = context.evaluator.getClassType(containingClass)
                        val inheritanceChain =
                            getInheritanceChain(receiverType, containingType)
                        if (inheritanceChain != null) {
                            for (type in inheritanceChain) {
                                val expressionOwner = evaluator.getQualifiedName(type)
                                if (expressionOwner != null && expressionOwner != owner) {
                                    val specificApi = apiDatabase.getMethodVersion(
                                        expressionOwner, name, desc
                                    )
                                    if (specificApi == NO_API_REQUIREMENT) {
                                        if (apiDatabase.isRelevantOwner(expressionOwner)) {
                                            return
                                        }
                                    } else if (specificApi <= context.project.minSdk) {
                                        return
                                    } else {
                                        // For example, for Bundle#getString(String,String) the
                                        // API level is 12, whereas for BaseBundle#getString
                                        // (String,String) the API level is 21. If the code
                                        // specified a Bundle instead of a BaseBundle, reported
                                        // the Bundle level in the error message instead.
                                        if (specificApi < api) {
                                            api = specificApi
                                        }
                                        api = Math.min(specificApi, api)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Unqualified call; need to search in our super hierarchy
                    // Unfortunately, expression.getReceiverType() does not work correctly
                    // in Java; it returns the type of the static binding of the call
                    // instead of giving the virtual dispatch type, as described in
                    // https://issuetracker.google.com/64528052 (and covered by
                    // for example ApiDetectorTest#testListView). Therefore, we continue
                    // to use the workaround method for Java (which isn't correct, and is
                    // particularly broken in Kotlin where the dispatch needs to take into
                    // account top level functions and extension methods), and then we use
                    // the correct receiver type in Kotlin.
                    var cls: PsiClass? = null
                    if (context.file.path.endsWith(DOT_JAVA)) {
                        cls = call.getContainingUClass()?.javaPsi
                    } else {
                        val receiverType = call.receiverType
                        if (receiverType is PsiClassType) {
                            cls = receiverType.resolve()
                        }
                    }
                    if (qualifier is UThisExpression || qualifier is USuperExpression) {
                        val pte = qualifier as UInstanceExpression
                        val resolved = pte.resolve()
                        if (resolved is PsiClass) {
                            cls = resolved
                        }
                    }
                    while (cls != null) {
                        if (cls is PsiAnonymousClass) {
                            // If it's an unqualified call in an anonymous class, we need to
                            // rely on the resolve method to find out whether the method is
                            // picked up from the anonymous class chain or any outer classes
                            var found = false
                            val anonymousBaseType = cls.baseClassType
                            val anonymousBase = anonymousBaseType.resolve()
                            if (anonymousBase != null && anonymousBase.isInheritor(
                                    containingClass,
                                    true
                                )
                            ) {
                                cls = anonymousBase
                                found = true
                            } else {
                                val surroundingBaseType =
                                    PsiTreeUtil.getParentOfType(cls, PsiClass::class.java, true)
                                if (surroundingBaseType != null && surroundingBaseType.isInheritor(
                                        containingClass,
                                        true
                                    )
                                ) {
                                    cls = surroundingBaseType
                                    found = true
                                }
                            }
                            if (!found) {
                                break
                            }
                        }
                        val expressionOwner = evaluator.getQualifiedName(cls)
                        if (expressionOwner == null || equivalentName(
                                expressionOwner,
                                "java/lang/Object"
                            )
                        ) {
                            break
                        }
                        val specificApi =
                            apiDatabase.getMethodVersion(expressionOwner, name, desc)
                        if (specificApi == NO_API_REQUIREMENT) {
                            if (apiDatabase.isRelevantOwner(expressionOwner)) {
                                break
                            }
                        } else if (specificApi <= context.project.minSdk) {
                            return
                        } else {
                            if (specificApi < api) {
                                api = specificApi
                            }
                            api = Math.min(specificApi, api)
                            break
                        }
                        cls = cls.superClass
                    }
                }
            }
            if (call.isMethodCall()) {
                val receiver = call.receiver
                var target: PsiClass? = null
                if (!method.isConstructor) {
                    if (receiver != null) {
                        val type = receiver.getExpressionType()
                        if (type is PsiClassType) {
                            target = type.resolve()
                        }
                    } else {
                        target = call.getContainingUClass()?.javaPsi
                    }
                }
                // Look to see if there's a possible local receiver
                if (target != null) {
                    val methods = target.findMethodsBySignature(method, true)
                    if (methods.size > 1) {
                        for (m in methods) {
                            if (!method.isEquivalentTo(m)) {
                                val provider = m.containingClass
                                if (provider != null) {
                                    val methodOwner = evaluator.getQualifiedName(provider)
                                    if (methodOwner != null) {
                                        val methodApi = apiDatabase.getMethodVersion(
                                            methodOwner, name, desc
                                        )
                                        if (methodApi == NO_API_REQUIREMENT ||
                                            methodApi <= context.project.minSdk
                                        ) {
                                            // Yes, we found another call that doesn't have an
                                            // API requirement
                                            return
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // If you're simply calling super.X from method X, even if method X is in a higher
                // API level than the minSdk, we're generally safe; that method should only be
                // called by the framework on the right API levels. (There is a danger of somebody
                // calling that method locally in other contexts, but this is hopefully unlikely.)
                if (receiver is USuperExpression) {
                    val containingMethod = call.getContainingUMethod()?.javaPsi
                    if (containingMethod != null &&
                        name == containingMethod.name &&
                        evaluator.areSignaturesEqual(method, containingMethod) &&
                        // We specifically exclude constructors from this check, because we
                        // do want to flag constructors requiring the new API level; it's
                        // highly likely that the constructor is called by local code so
                        // you should specifically investigate this as a developer
                        !method.isConstructor
                    ) {
                        return
                    }
                }
                // If it's a method we have source for, obviously it shouldn't be a
                // violation, happens in androidx (appcompat?)
                if (method !is PsiCompiledElement) {
                    return
                }
            }
            // Desugar rewrites compare calls (see b/36390874)
            if (name == "compare" &&
                api == 19 &&
                startsWithEquivalentPrefix(owner, "java/lang/") &&
                desc.length == 4 &&
                context.project.isDesugaring(Desugaring.LONG_COMPARE) &&
                (
                    desc == "(JJ)" ||
                        desc == "(ZZ)" ||
                        desc == "(BB)" ||
                        desc == "(CC)" ||
                        desc == "(II)" ||
                        desc == "(SS)"
                    )
            ) {
                return
            }
            // Desugar rewrites Objects.requireNonNull calls (see b/32446315)
            if (name == "requireNonNull" &&
                api == 19 &&
                owner == "java.util.Objects" &&
                desc == "(Ljava.lang.Object;)" &&
                context.project.isDesugaring(Desugaring.OBJECTS_REQUIRE_NON_NULL)
            ) {
                return
            }
            if (name == "addSuppressed" &&
                api == 19 &&
                owner == "java.lang.Throwable" &&
                desc == "(Ljava.lang.Throwable;)" &&
                context.project.isDesugaring(Desugaring.TRY_WITH_RESOURCES)
            ) {
                return
            }
            val nameIdentifier = call.methodIdentifier
            val location = if (call.isConstructorCall() &&
                call.classReference != null
            ) {
                context.getRangeLocation(call, 0, call.classReference!!, 0)
            } else if (nameIdentifier != null) {
                context.getLocation(nameIdentifier)
            } else {
                context.getLocation(reference)
            }
            if (call.getContainingUClass() == null) {
                // Can't verify if containing class is annotated with @RequiresApi
                return
            }
            val potentialRequiresApiVersion = getRequiresApiFromAnnotations(
                call.getContainingUClass()!!.javaPsi
            )
            if (potentialRequiresApiVersion == NO_API_REQUIREMENT ||
                api > potentialRequiresApiVersion
            ) {
                val containingClassName = call.getContainingUClass()!!.qualifiedName.toString()
                val fix = createLintFix(method, call, api)

                context.report(
                    ISSUE, reference, location,
                    "This call references a method added in API level $api; however, the " +
                        "containing class $containingClassName is reachable from earlier API " +
                        "levels and will fail run-time class verification.",
                    fix,
                )
            }
        }

        /**
         * Attempts to create a [LintFix] for the call to specified method.
         *
         * @return a lint fix, or `null` if no fix could be created
         */
        private fun createLintFix(
            method: PsiMethod,
            call: UCallExpression,
            api: Int
        ): LintFix? {
            if (isKotlin(call.sourcePsi)) {
                // We only support Java right now.
                return null
            }

            // The host class should never be null if we're looking at Java code.
            val callContainingClass = call.getContainingUClass() ?: return null

            val (wrapperMethodName, methodForInsertion) = generateWrapperMethod(
                method
            ) ?: return null

            val (wrapperClassName, insertionPoint, insertionSource) = generateInsertionSource(
                api,
                callContainingClass,
                methodForInsertion
            )

            val replacementCall = generateWrapperCall(
                method,
                call.receiver,
                call.valueArguments,
                wrapperClassName,
                wrapperMethodName
            ) ?: return null

            return fix().name("Extract to static inner class")
                .composite(
                    fix()
                        .replace()
                        .range(insertionPoint)
                        .beginning()
                        .with(insertionSource)
                        .reformat(true)
                        .shortenNames()
                        .build(),
                    fix()
                        .replace()
                        .range(context.getLocation(call))
                        .with(replacementCall)
                        .reformat(true)
                        .shortenNames()
                        .build(),
                )
        }

        /**
         * Generates source code for a wrapper method and class (where applicable) and calculates
         * the insertion point. If the wrapper class already exists, returns source code for the
         * method body only with an insertion point at the end of the existing wrapper class body.
         *
         * Source code follows the general format:
         *
         * ```java
         * @RequiresApi(21)
         * static class Api21Impl {
         *   private Api21Impl() {}
         *   // Method body here.
         * }
         * ```
         *
         * @param api API level at which the platform method can be safely called
         * @param callContainingClass Class containing the call to the platform method
         * @param wrapperMethodBody Source code for the wrapper method
         * @return Triple containing (1) the name of the static wrapper class, (2) the insertion
         * point for the generated source code, and (3) generated source code for a static wrapper
         * method, including a static wrapper class if necessary
         */
        private fun generateInsertionSource(
            api: Int,
            callContainingClass: UClass,
            wrapperMethodBody: String,
        ): Triple<String, Location, String> {
            val wrapperClassName = "Api${api}Impl"
            val implInsertionPoint: Location
            val implForInsertion: String

            val existingWrapperClass = callContainingClass.innerClasses.find { innerClass ->
                innerClass.name == wrapperClassName
            }

            if (existingWrapperClass == null) {
                implInsertionPoint = context.getLocation(callContainingClass.lastChild)
                implForInsertion = """
                @androidx.annotation.RequiresApi($api)
                static class $wrapperClassName {
                    private $wrapperClassName() {
                        // This class is not instantiable.
                    }
                    $wrapperMethodBody
                }
                """.trimIndent()
            } else {
                implInsertionPoint = context.getLocation(existingWrapperClass.lastChild)
                implForInsertion = wrapperMethodBody.trimIndent()
            }

            return Triple(
                wrapperClassName,
                implInsertionPoint,
                implForInsertion
            )
        }

        /**
         * Generates source code for a call to the generated wrapper method, or `null` if we don't
         * know how to do that. Currently, this method is capable of handling static calls --
         * including constructor calls -- and simple reference expressions from Java source code.
         *
         * Source code follows the general format:
         *
         * ```
         * WrapperClassName.wrapperMethodName(receiverVar, argumentVar)
         * ```
         *
         * @param method Platform method which is being called
         * @param callReceiver Receiver of the call to the platform method
         * @param callValueArguments Arguments of the call to the platform method
         * @param wrapperClassName Name of the generated wrapper class
         * @param wrapperMethodName Name of the generated wrapper method
         * @return Source code for a call to the static wrapper method
         */
        private fun generateWrapperCall(
            method: PsiMethod,
            callReceiver: UExpression?,
            callValueArguments: List<UExpression>,
            wrapperClassName: String,
            wrapperMethodName: String,
        ): String? {
            val isStatic = context.evaluator.isStatic(method)
            val isConstructor = method.isConstructor
            val isSimpleReference = callReceiver is JavaUSimpleNameReferenceExpression

            val callReceiverStr = when {
                isStatic -> null
                isConstructor -> null
                isSimpleReference ->
                    (callReceiver as JavaUSimpleNameReferenceExpression).identifier
                else -> {
                    // We don't know how to handle this type of receiver. This should never happen.
                    return null
                }
            }

            val callValues = if (callValueArguments.isNotEmpty()) {
                callValueArguments.joinToString(separator = ", ") { argument ->
                    argument.asSourceString()
                }
            } else {
                null
            }

            val replacementArgs = listOfNotNull(callReceiverStr, callValues).joinToString(", ")

            return "$wrapperClassName.$wrapperMethodName($replacementArgs)"
        }

        /**
         * Generates source code for a wrapper method, or `null` if we don't know how to do that.
         * Currently, this method is capable of handling method and constructor calls from Java
         * source code.
         *
         * Source code follows the general format:
         *
         * ```
         * @DoNotInline
         * static ReturnType methodName(HostType hostType, ParamType paramType) {
         *   return hostType.methodName(paramType);
         * }
         * ```
         *
         * @param method Platform method which is being called
         * @return Pair containing (1) the name of the static wrapper method and (2) generated
         * source code for a static wrapper around the platform method
         */
        private fun generateWrapperMethod(method: PsiMethod): Pair<String, String>? {
            val methodName = method.name
            val evaluator = context.evaluator
            val isStatic = evaluator.isStatic(method)
            val isConstructor = method.isConstructor

            // Neither of these should be null if we're looking at Java code.
            val containingClass = method.containingClass ?: return null
            val hostType = containingClass.name ?: return null
            val hostVar = hostType[0].toLowerCase() + hostType.substring(1)

            val hostParam = if (isStatic || isConstructor) { null } else { "$hostType $hostVar" }

            val typeParamsStr = if (method.typeParameters.isNotEmpty()) {
                "<${method.typeParameters.joinToString(", ") { param -> "${param.name}" }}> "
            } else {
                ""
            }

            val typedParams = method.parameters.map { param ->
                "${(param.type as? PsiType)?.presentableText} ${param.name}"
            }
            val typedParamsStr = (listOfNotNull(hostParam) + typedParams).joinToString(", ")

            val namedParamsStr = method.parameters.joinToString(separator = ", ") { param ->
                "${param.name}"
            }

            val wrapperMethodName: String
            val returnTypeStr: String
            val returnStmtStr: String
            val receiverStr: String

            if (isConstructor) {
                wrapperMethodName = "create$methodName"
                returnTypeStr = hostType
                returnStmtStr = "return "
                receiverStr = "new "
            } else {
                wrapperMethodName = methodName
                returnTypeStr = method.returnType?.presentableText ?: "void"
                returnStmtStr = if ("void" == returnTypeStr) "" else "return "
                receiverStr = if (isStatic) "$hostType." else "$hostVar."
            }

            return Pair(
                wrapperMethodName,
                """
                    @androidx.annotation.DoNotInline
                    static $typeParamsStr$returnTypeStr $wrapperMethodName($typedParamsStr) {
                        $returnStmtStr$receiverStr$methodName($namedParamsStr);
                    }
                """
            )
        }

        private fun getInheritanceChain(
            derivedClass: PsiClassType,
            baseClass: PsiClassType?
        ): List<PsiClassType>? {
            if (derivedClass == baseClass) {
                return emptyList()
            }
            val chain = getInheritanceChain(derivedClass, baseClass, HashSet(), 0)
            chain?.reverse()
            return chain
        }

        private fun getInheritanceChain(
            derivedClass: PsiClassType,
            baseClass: PsiClassType?,
            visited: HashSet<PsiType>,
            depth: Int
        ): MutableList<PsiClassType>? {
            if (derivedClass == baseClass) {
                return ArrayList(depth)
            }
            for (type in derivedClass.superTypes) {
                if (visited.add(type) && type is PsiClassType) {
                    val chain = getInheritanceChain(type, baseClass, visited, depth + 1)
                    if (chain != null) {
                        chain.add(derivedClass)
                        return chain
                    }
                }
            }
            return null
        }

        private fun getRequiresApiFromAnnotations(modifierListOwner: PsiModifierListOwner): Int {
            for (annotation in context.evaluator.getAllAnnotations(modifierListOwner, false)) {
                val qualifiedName = annotation.qualifiedName
                if (REQUIRES_API_ANNOTATION.isEquals(qualifiedName)) {
                    val wrapped = JavaUAnnotation.wrap(annotation)
                    var api = getLongAttribute(
                        context, wrapped,
                        ATTR_VALUE, NO_API_REQUIREMENT.toLong()
                    ).toInt()
                    if (api <= 1) {
                        // @RequiresApi has two aliasing attributes: api and value
                        api = getLongAttribute(context, wrapped, "api", NO_API_REQUIREMENT.toLong())
                            .toInt()
                    }
                    return api
                } else if (qualifiedName == null) {
                    // Work around UAST type resolution problems
                    // Work around bugs in UAST type resolution for file annotations:
                    // parse the source string instead.
                    if (annotation is PsiCompiledElement) {
                        continue
                    }
                    val text = annotation.text
                    if (text.contains("RequiresApi(")) {
                        val start = text.indexOf('(')
                        val end = text.indexOf(')', start + 1)
                        if (end != -1) {
                            var name = text.substring(start + 1, end)
                            // Strip off attribute name and qualifiers, e.g.
                            //   @RequiresApi(api = Build.VERSION.O) -> O
                            var index = name.indexOf('=')
                            if (index != -1) {
                                name = name.substring(index + 1).trim()
                            }
                            index = name.indexOf('.')
                            if (index != -1) {
                                name = name.substring(index + 1)
                            }
                            if (!name.isEmpty()) {
                                if (name[0].isDigit()) {
                                    val api = Integer.parseInt(name)
                                    if (api > 0) {
                                        return api
                                    }
                                } else {
                                    return codeNameToApi(name)
                                }
                            }
                        }
                    }
                }
            }
            return NO_API_REQUIREMENT
        }
    }

    companion object {
        const val NO_API_REQUIREMENT = -1
        val ISSUE = Issue.create(
            "ClassVerificationFailure",
            "Even in cases where references to new APIs are gated on SDK_INT " +
                "checks, run-time class verification will still fail on references to APIs that " +
                "may not be available at run time, including platform APIs introduced after a " +
                "library's minSdkVersion.",
            """
                The Java language requires a virtual machine to verify the class files it
                loads and executes. A class may fail verification for a wide variety of
                reasons, but in practice it‘s usually because the class’s code refers to
                unknown classes or methods.
                
                References to APIs added after a library's minSdkVersion -- regardless of
                any surrounding version checks -- will fail run-time class verification if
                the API does not exist on the device, leading to reduced run-time
                performance.

                Gating references on SDK checks alone DOES NOT address class verification
                failures.

                To prevent class verification failures, references to new APIs must be
                moved to inner classes that are only initialized inside of an appropriate
                SDK check.

                For example, if our minimum SDK is 14 and platform method a.x(params...)
                was added in SDK 16, the method call must be moved to an inner class like:

                @RequiresApi(16)
                private static class Api16Impl{
                  @DoNotInline
                  static void callX(params...) {
                    a.x(params...);
                  }
                }

                The call site is changed from a.x(params...) to Api16Impl.callX(params).

                Since ART will only try to optimize Api16Impl when it's on the execution
                path, we are guaranteed to have a.x(...) available.

                In addition, optimizers like R8 or Proguard may inline the method in the
                separate class and replace the wrapper call with the actual call, so you
                must disable inlining using the @DoNotInline annotation.

                Failure to do the above may result in overall performance degradation.
            """,
            Category.CORRECTNESS, 5, Severity.ERROR,
            Implementation(ClassVerificationFailureDetector::class.java, Scope.JAVA_FILE_SCOPE)
        ).setAndroidSpecific(true)
    }
}
