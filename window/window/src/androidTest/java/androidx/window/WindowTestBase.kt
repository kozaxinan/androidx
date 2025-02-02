/*
 * Copyright 2021 The Android Open Source Project
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
@file:Suppress("DEPRECATION") // TODO(b/185594766) Need to migrate to ActivityScenario

package androidx.window

import android.app.Activity
import android.os.IBinder
import androidx.test.rule.ActivityTestRule

/**
 * Base class for all tests in the module.
 */
public open class WindowTestBase {
    @JvmField
    public val activityTestRule: ActivityTestRule<TestActivity> =
        ActivityTestRule(TestActivity::class.java, false, true)

    public companion object {
        @JvmStatic
        public fun getActivityWindowToken(activity: Activity): IBinder {
            return activity.window.attributes.token
        }
    }
}
