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

package androidx.car.app.activity;

import static android.content.pm.PackageManager.NameNotFoundException;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.activity.renderer.ICarAppActivity;
import androidx.car.app.activity.renderer.IRendererCallback;
import androidx.car.app.activity.renderer.IRendererService;
import androidx.car.app.activity.renderer.surface.ISurfaceListener;
import androidx.car.app.activity.renderer.surface.OnBackPressedListener;
import androidx.car.app.activity.renderer.surface.SurfaceHolderListener;
import androidx.car.app.activity.renderer.surface.SurfaceWrapperProvider;
import androidx.car.app.activity.renderer.surface.TemplateSurfaceView;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.serialization.BundlerException;
import androidx.car.app.utils.ThreadUtils;
import androidx.fragment.app.FragmentActivity;

import java.util.List;

/**
 * The class representing a car app activity.
 *
 * <p>This class is responsible for binding to the host and rendering the content given by a {@link
 * androidx.car.app.CarAppService}.
 *
 * <p>Usage of {@link CarAppActivity} is only required for applications targeting Automotive OS.
 *
 * <h4>Activity Declaration</h4>
 *
 * <p>The app must declare an {@code activity-alias} for a {@link CarAppActivity} providing its
 * associated {@link androidx.car.app.CarAppService} as meta-data. For example:
 *
 * <pre>{@code
 * <activity-alias
 *   android:enabled="true"
 *   android:exported="true"
 *   android:label="@string/your_app_label"
 *   android:name=".YourActivityAliasName"
 *   android:targetActivity="androidx.car.app.activity.CarAppActivity" >
 *   <intent-filter>
 *     <action android:name="android.intent.action.MAIN" />
 *     <category android:name="android.intent.category.LAUNCHER" />
 *   </intent-filter>
 *   <meta-data
 *     android:name="androidx.car.app.CAR_APP_SERVICE"
 *     android:value=".YourCarAppService" />
 *   <meta-data android:name="distractionOptimized" android:value="true"/>
 * </activity-alias>
 * }</pre>
 *
 * <p>See {@link androidx.car.app.CarAppService} for how to declare your app's car app service in
 * the manifest.
 *
 * <p>Note the name of the alias should be unique and resemble a fully qualified class name, but
 * unlike the name of the target activity, the alias name is arbitrary; it does not refer to an
 * actual class.
 */
// TODO(b/179225768): Remove distractionOptimized from the javadoc above if we can make that
// implicit for car apps.
@SuppressLint({"ForbiddenSuperClass"})
public final class CarAppActivity extends FragmentActivity {
    @VisibleForTesting
    static final String SERVICE_METADATA_KEY = "androidx.car.app.CAR_APP_SERVICE";
    private static final String TAG = "CarAppActivity";

    @SuppressLint({"ActionValue"})
    @VisibleForTesting
    static final String ACTION_RENDER = "android.car.template.host.RendererService";

    @Nullable
    private ComponentName mServiceComponentName;
    TemplateSurfaceView mSurfaceView;
    SurfaceHolderListener mSurfaceHolderListener;
    ActivityLifecycleDelegate mActivityLifecycleDelegate;
    @Nullable
    OnBackPressedListener mOnBackPressedListener;
    ServiceDispatcher mServiceDispatcher;
    private int mDisplayId;

    /**
     * Handles the service connection errors by presenting a message the user and potentially
     * finishing the activity.
     */
    final ErrorHandler mErrorHandler = (errorType, exception) -> {
        requireNonNull(errorType);

        Log.e(LogTags.TAG, "Service error: " + errorType, exception);

        unbindService();

        ThreadUtils.runOnMain(() -> {
            Log.d(LogTags.TAG, "Showing error fragment");

            if (mSurfaceView != null) {
                mSurfaceView.setVisibility(View.GONE);
            }

            getSupportFragmentManager()
                    .beginTransaction()
                    .add(
                            R.id.error_message_container,
                            ErrorMessageFragment.newInstance(errorType))
                    .commit();
        });
    };

    /**
     * {@link ICarAppActivity} implementation that allows the {@link IRendererService} to
     * communicate with this {@link CarAppActivity}.
     */
    private final ICarAppActivity.Stub mCarActivity =
            new ICarAppActivity.Stub() {
                @Override
                public void setSurfacePackage(@NonNull Bundleable bundleable) {
                    requireNonNull(bundleable);
                    try {
                        Object surfacePackage = bundleable.get();
                        ThreadUtils.runOnMain(() -> mSurfaceView.setSurfacePackage(surfacePackage));
                    } catch (BundlerException e) {
                        mErrorHandler.onError(ErrorHandler.ErrorType.HOST_ERROR, e);
                    }
                }

                @Override
                public void registerRendererCallback(@NonNull IRendererCallback callback) {
                    requireNonNull(callback);
                    ThreadUtils.runOnMain(
                            () -> {
                                mSurfaceView.setOnCreateInputConnectionListener(editorInfo ->
                                        mServiceDispatcher.fetch(null, () ->
                                                callback.onCreateInputConnection(
                                                        editorInfo)));

                                mOnBackPressedListener = () ->
                                        mServiceDispatcher.dispatch(callback::onBackPressed);

                                mActivityLifecycleDelegate.registerRendererCallback(callback);
                            });
                }

                @Override
                public void setSurfaceListener(@NonNull ISurfaceListener listener) {
                    requireNonNull(listener);
                    ThreadUtils.runOnMain(
                            () -> mSurfaceHolderListener.setSurfaceListener(listener));
                }

                @Override
                public void onStartInput() {
                    ThreadUtils.runOnMain(() -> mSurfaceView.onStartInput());
                }

                @Override
                public void onStopInput() {
                    ThreadUtils.runOnMain(() -> mSurfaceView.onStopInput());
                }

                @Override
                public void startCarApp(@NonNull Intent intent) {
                    startActivity(intent);
                }

                @Override
                public void finishCarApp() {
                    finish();
                }
            };

    /** The service connection for the renderer service. */
    private ServiceConnection mServiceConnectionImpl =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(
                        @NonNull ComponentName name, @NonNull IBinder service) {
                    requireNonNull(name);
                    requireNonNull(service);
                    Log.i(LogTags.TAG, String.format("Host service %s is connected",
                            name.flattenToShortString()));
                    IRendererService rendererService = IRendererService.Stub.asInterface(service);
                    if (rendererService == null) {
                        mErrorHandler.onError(ErrorHandler.ErrorType.HOST_INCOMPATIBLE,
                                new Exception("Failed to get IRenderService binder from host: "
                                        + name));
                        return;
                    }

                    mServiceDispatcher.setRendererService(rendererService);
                    verifyServiceVersion(rendererService);
                    initializeService(rendererService);
                    updateIntent(getIntent());
                }

                @Override
                public void onServiceDisconnected(@NonNull ComponentName name) {
                    requireNonNull(name);

                    // Connection lost, but it might reconnect.
                    Log.w(LogTags.TAG, String.format("Host service %s is disconnected",
                            name.flattenToShortString()));
                }

                @Override
                public void onBindingDied(@NonNull ComponentName name) {
                    requireNonNull(name);

                    // Connection permanently lost
                    mErrorHandler.onError(ErrorHandler.ErrorType.HOST_CONNECTION_LOST,
                            new Exception("Host service " + name + " is permanently disconnected"));
                }

                @Override
                public void onNullBinding(@NonNull ComponentName name) {
                    requireNonNull(name);

                    // Host rejected the binding.
                    mErrorHandler.onError(ErrorHandler.ErrorType.HOST_INCOMPATIBLE,
                            new Exception("Host service " + name + " rejected the binding "
                                    + "request"));
                }
            };

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mServiceDispatcher = new ServiceDispatcher(mErrorHandler);
        setContentView(R.layout.activity_template);
        mSurfaceView = requireViewById(R.id.template_view_surface);
        mActivityLifecycleDelegate = new ActivityLifecycleDelegate(mServiceDispatcher);
        mSurfaceHolderListener = new SurfaceHolderListener(mServiceDispatcher,
                new SurfaceWrapperProvider(mSurfaceView));

        mServiceComponentName = retrieveServiceComponentName();
        if (mServiceComponentName == null) {
            Log.e(TAG, "Unspecified service class name");
            finish();
            return;
        }

        registerActivityLifecycleCallbacks(mActivityLifecycleDelegate);

        // Set the z-order to receive the UI events on the surface.
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceView.setServiceDispatcher(mServiceDispatcher);
        mSurfaceView.setErrorHandler(mErrorHandler);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderListener);
        mDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        bindService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSurfaceView != null) {
            mSurfaceView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSurfaceView != null) {
            mSurfaceView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            unbindService();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mOnBackPressedListener != null) {
            mOnBackPressedListener.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        if (!mServiceDispatcher.isBound()) {
            bindService();
        } else {
            updateIntent(intent);
        }
    }

    @VisibleForTesting
    ServiceConnection getServiceConnection() {
        return mServiceConnectionImpl;
    }

    @VisibleForTesting
    void setServiceConnection(ServiceConnection serviceConnection) {
        mServiceConnectionImpl = serviceConnection;
    }

    @VisibleForTesting
    int getDisplayId() {
        return mDisplayId;
    }

    @Nullable
    private ComponentName retrieveServiceComponentName() {
        ActivityInfo activityInfo = null;
        try {
            activityInfo =
                    getPackageManager()
                            .getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to find component: " + getComponentName(), e);
        }

        if (activityInfo == null) {
            return null;
        }

        String serviceName = activityInfo.metaData.getString(SERVICE_METADATA_KEY);
        if (serviceName == null) {
            Log.e(
                    TAG,
                    "Unable to find required metadata tag with name "
                            + SERVICE_METADATA_KEY
                            + ". App manifest must include metadata tag with name "
                            + SERVICE_METADATA_KEY
                            + " and the name of the car app service as the value");
            return null;
        }

        return new ComponentName(this, serviceName);
    }

    /** Binds to the renderer service. */
    private void bindService() {
        Intent rendererIntent = new Intent(ACTION_RENDER);
        List<ResolveInfo> resolveInfoList =
                getPackageManager()
                        .queryIntentServices(rendererIntent, PackageManager.GET_META_DATA);
        if (resolveInfoList.size() == 1) {
            rendererIntent.setPackage(resolveInfoList.get(0).serviceInfo.packageName);
            if (!bindService(
                    rendererIntent,
                    mServiceConnectionImpl,
                    Context.BIND_AUTO_CREATE | Context.BIND_INCLUDE_CAPABILITIES)) {
                mErrorHandler.onError(ErrorHandler.ErrorType.HOST_INCOMPATIBLE,
                        new Exception("Cannot bind to the renderer host with intent: "
                                + rendererIntent));
            }
        } else if (resolveInfoList.isEmpty()) {
            mErrorHandler.onError(ErrorHandler.ErrorType.HOST_NOT_FOUND, new Exception("No "
                    + "handlers found for intent: " + rendererIntent));
        } else {
            StringBuilder logMessage =
                    new StringBuilder("Multiple hosts found, only one is allowed");
            for (ResolveInfo resolveInfo : resolveInfoList) {
                logMessage.append(
                        String.format("\nFound host %s", resolveInfo.serviceInfo.packageName));
            }
            mErrorHandler.onError(ErrorHandler.ErrorType.MULTIPLE_HOSTS,
                    new Exception(logMessage.toString()));
        }
    }

    /**
     * Verifies that the renderer service supports the current version.
     *
     * @param rendererService the renderer service which should verify the version
     */
    void verifyServiceVersion(IRendererService rendererService) {
        // TODO(169604451) Add version support logic
        boolean isCompatible = true;

        if (!isCompatible) {
            mErrorHandler.onError(ErrorHandler.ErrorType.HOST_INCOMPATIBLE,
                    new Exception("Renderer service unsupported"));
        }
    }

    /**
     * Initializes the {@code rendererService} for the current activity with {@code carActivity},
     * {@code serviceComponentName} and {@code displayId}.
     *
     * @param rendererService the renderer service that needs to be initialized
     */
    void initializeService(@NonNull IRendererService rendererService) {
        requireNonNull(rendererService);
        requireNonNull(mServiceComponentName);
        ComponentName serviceComponentName = mServiceComponentName;
        Boolean success = mServiceDispatcher.fetch(false,
                () -> rendererService.initialize(mCarActivity,
                        serviceComponentName, mDisplayId));
        if (success == null || !success) {
            mErrorHandler.onError(ErrorHandler.ErrorType.HOST_ERROR,
                    new Exception("Cannot create renderer for" + mServiceComponentName));
        }
    }

    /** Closes the connection to the connected {@code rendererService} if any. */
    void unbindService() {
        // Remove the renderer callback since there is no need to communicate the state with
        // the host.
        mActivityLifecycleDelegate.registerRendererCallback(null);
        // Stop sending SurfaceView updates
        mSurfaceView.getHolder().removeCallback(mSurfaceHolderListener);
        // If host has already disconnected, there is no need for an unbind.
        IRendererService rendererService = mServiceDispatcher.getRendererService();
        if (rendererService == null) {
            return;
        }
        try {
            rendererService.terminate(requireNonNull(mServiceComponentName));
        } catch (RemoteException e) {
            // We are already unbinding (maybe because the host has already cut the connection)
            // Let's not log more errors unnecessarily.
        }

        Log.i(LogTags.TAG, "Unbinding from " + mServiceComponentName);
        unbindService(mServiceConnectionImpl);
        mServiceDispatcher.setRendererService(null);
    }

    /**
     * Updates the activity intent for the {@code rendererService}.
     */
    void updateIntent(Intent intent) {
        requireNonNull(mServiceComponentName);
        IRendererService service = mServiceDispatcher.getRendererService();
        if (service == null) {
            mErrorHandler.onError(ErrorHandler.ErrorType.CLIENT_SIDE_ERROR,
                    new Exception("Service dispatcher is not connected"));
            return;
        }
        ComponentName serviceComponentName = mServiceComponentName;
        Boolean success = mServiceDispatcher.fetch(false, () ->
                service.onNewIntent(intent, serviceComponentName, mDisplayId));
        if (success == null || !success) {
            mErrorHandler.onError(ErrorHandler.ErrorType.HOST_ERROR, new Exception("Renderer "
                    + "cannot handle the intent: " + intent));
        }
    }
}
