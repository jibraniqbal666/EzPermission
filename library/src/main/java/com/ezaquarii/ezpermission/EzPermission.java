// Copyright (C) EzPermission by Krzysztof Narkiewicz (hello@ezaquarii.com)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ezaquarii.ezpermission;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * This helper allows to dispatch an action that requires permissions.
 *
 * If permission is not granted, it will handle permission request flow,
 * including rationale and permission re-query.
 *
 * It can handle modal rationale, like an info dialog, and non-modal, when we
 * want to switch entire screen into certain mode.
 *
 * It handles permission revocation and grant via system settings too.
 */

/*
@startuml

[*] --> START : [isRationaleModal]
[*] --> RATIONALE : [!isRationaleModal]
START --> RATIONALE : DISPATCH\n[!isGranted &&\ncanShowRationale]
START --> REQUESTING : DISPATCH\n[!isGranted &&\n!canShowRationale]
START --> GRANTED : DISPATCH\n[isGranted]
RATIONALE ---> REQUESTING : DISPATCH
REQUESTING --> GRANTED : GRANTED
REQUESTING --> START : DENIED / onDenied()\n[canShowRationale &&\nisRationaleModal]
REQUESTING --> RATIONALE : DENIED / onDenied()\n[canShowRationale &&\n!isRationaleModal]
REQUESTING --> DENIED : DENIED\n[!canShowRationale]
GRANTED --> GRANTED : DISPATCH\n[isGranted]
GRANTED --> DENIED : DISPATCH\n[!isGranted]
DENIED --> DENIED : DISPATCH\n[!isGranted]
DENIED --> GRANTED : DISPATCH\n[isGranted]

GRANTED : entry: onGranted()
RATIONALE : entry: onRationale()
REQUESTING : entry: onRequest()
DENIED : entry: onDeniedPermanently()

@enduml
*/
public class EzPermission {

    private static final String TAG = EzPermission.class.getSimpleName();
    private static final String EXTRA_INSTANCE_STATE_DEBUG = EzPermission.class.getName() + ".DEGUG";
    private static final String EXTRA_INSTANCE_STATE_FSM_STATE = EzPermission.class.getName() + ".FSM_STATE";

    public static class Builder {

        private Activity mActivity = null;
        private Fragment mFragment = null;
        private String[] mPermissions = null;
        private int mRequestCode = 0;
        private boolean mIsModal = false;
        private Runnable mOnGranted = null;
        private Runnable mOnRequest = null;
        private Runnable mOnRationale = null;
        private Runnable mOnDenied = null;
        private Runnable mOnDeniedPermanently = null;
        private Callable<Boolean> mCanShowRationale = null;
        private Callable<Boolean> mIsPermissionGranted = null;

        Builder(Activity activity, int requestCode, String[] permissions) {
            mActivity = activity;
            mRequestCode = requestCode;
            mPermissions = permissions;
        }

        Builder(Fragment fragment, int requestCode, String[] permissions) {
            mFragment = fragment;
            mRequestCode = requestCode;
            mPermissions = permissions;
        }

        public Builder isModal(boolean isModal) {
            mIsModal = isModal;
            return this;
        }

        public Builder onRequest(Runnable onRequest) {
            mOnRequest = onRequest;
            return this;
        }

        public Builder onRationale(Runnable onRationale) {
            mOnRationale = onRationale;
            return this;
        }

        public Builder onGranted(Runnable onGranted) {
            mOnGranted = onGranted;
            return this;
        }

        public Builder onDenied(Runnable onDenied) {
            mOnDenied = onDenied;
            return this;
        }

        public Builder onDeniedPermanantly(Runnable onDeniedPermanantly) {
            mOnDeniedPermanently = onDeniedPermanantly;
            return this;
        }

        public Builder canShowRationale(Callable<Boolean> canShowRationale) {
            mCanShowRationale = canShowRationale;
            return this;
        }

        public Builder isPermissionGranted(Callable<Boolean> isPermissionGranted) {
            mIsPermissionGranted = isPermissionGranted;
            return this;
        }

        public EzPermission build() {
            boolean noContext = mActivity == null && mFragment == null;
            if(noContext) {
                if(mOnRequest == null || mIsPermissionGranted == null || mCanShowRationale == null) {
                    throw new IllegalArgumentException("You must provide activity or fragment or onRequest, isPermissionGranted and canShowRationale.");
                }
            }

            return new EzPermission(mActivity,
                                    mFragment,
                                    mRequestCode,
                                    mIsModal,
                                    mPermissions,
                                    mOnGranted,
                                    mOnRationale,
                                    mOnDenied,
                                    mOnDeniedPermanently,
                                    mOnRequest,
                                    mCanShowRationale,
                                    mIsPermissionGranted);
        }

    }

    private interface Predicate<T> {
        boolean test(T item);
    }

    static class Fsm {

        enum State {
            START,
            RATIONALE,
            REQUESTING,
            GRANTED,
            DENIED
        }

        enum Event {
            DISPATCH,
            GRANTED,
            DENIED,
            REJECT
        }

        private static class Row {
            Row(State from, Event event, Callable<Boolean> guard, State to) {
                this.event = event;
                this.guard = guard;
                this.from = from;
                this.to = to;
            }

            Event event;
            Callable<Boolean> guard;
            State from;
            State to;

            boolean evaluate(State currentState, Event event) {
                try {
                    return this.from == currentState &&
                            this.event == event &&
                            (guard == null || guard.call());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        private static class StatePair {

            public final State from;
            public final State to;

            StatePair(State from, State to) {
                this.from = from;
                this.to = to;
            }

            /// Autogenerated
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                StatePair that = (StatePair) o;

                if (from != that.from) return false;
                return to == that.to;
            }

            /// Autogenerated
            @Override
            public int hashCode() {
                int result = from != null ? from.hashCode() : 0;
                result = 31 * result + (to != null ? to.hashCode() : 0);
                return result;
            }
        }

        private Callable<Boolean> mIsGranted;
        private Callable<Boolean> mCanShowRationale;
        private Callable<Boolean> mIsRationaleModal;
        private Map<State, Runnable> mOnEntryCallbacks = new HashMap<>();
        private Map<StatePair, Runnable> mOnTransitionCallbacks = new HashMap<>();

        private Callable<Boolean> mNone = null;

        private Runnable mOnGranted;
        private Runnable mOnRationale;
        private Runnable mOnRequest;
        private Runnable mOnDenied;
        private Runnable mOnDeniedPermanently;

        private State mCurrentState = State.START;
        private boolean mDebug = false;
        private Row[] mFsmTable;

        Fsm(Callable<Boolean> isGranted, Callable<Boolean> canShowRationale, Callable<Boolean> isRationaleModal, Runnable onGranted, Runnable onRationale, Runnable onRequest, Runnable onDenied, Runnable onDeniedPermanently) {
            mIsGranted = isGranted;
            mCanShowRationale = canShowRationale;
            mIsRationaleModal = isRationaleModal;

            mOnGranted = onGranted;
            mOnRationale = onRationale;
            mOnRequest = onRequest;
            mOnDenied = onDenied;
            mOnDeniedPermanently = onDeniedPermanently;

            mFsmTable = new Row[] {
                    new Row(State.START,      Event.DISPATCH, mIsGranted,                        State.GRANTED),
                    new Row(State.START,      Event.DISPATCH, and(not(mIsGranted),
                                                                      mCanShowRationale),        State.RATIONALE),
                    new Row(State.START,      Event.DISPATCH, and(not(mIsGranted),
                                                                  not(mCanShowRationale)),       State.REQUESTING),
                    new Row(State.RATIONALE,  Event.DISPATCH, mNone,                             State.REQUESTING),
                    new Row(State.RATIONALE,  Event.REJECT,   mIsRationaleModal,                 State.START),
                    new Row(State.REQUESTING, Event.GRANTED,  mNone,                             State.GRANTED),
                    new Row(State.REQUESTING, Event.DENIED,   and(mCanShowRationale,
                                                                  mIsRationaleModal),            State.START),
                    new Row(State.REQUESTING, Event.DENIED,   and(mCanShowRationale,
                                                                  not(mIsRationaleModal)),       State.RATIONALE),
                    new Row(State.REQUESTING, Event.DENIED,   not(mCanShowRationale),            State.DENIED),
                    new Row(State.DENIED,     Event.DISPATCH, not(mIsGranted),                   State.DENIED),
                    new Row(State.DENIED,     Event.DISPATCH, mIsGranted,                        State.GRANTED),
                    new Row(State.GRANTED,    Event.DISPATCH, not(mIsGranted),                   State.DENIED),
                    new Row(State.GRANTED,    Event.DISPATCH, mIsGranted,                        State.GRANTED)
            };

            mOnEntryCallbacks.put(State.GRANTED, mOnGranted);
            mOnEntryCallbacks.put(State.RATIONALE, mOnRationale);
            mOnEntryCallbacks.put(State.REQUESTING, mOnRequest);
            mOnEntryCallbacks.put(State.DENIED, mOnDeniedPermanently);

            mOnTransitionCallbacks.put(new StatePair(State.REQUESTING, State.START), mOnDenied);
            mOnTransitionCallbacks.put(new StatePair(State.REQUESTING, State.RATIONALE), mOnDenied);
        }

        void start() {
            try {
                if (mIsRationaleModal.call()) {
                    mCurrentState = State.START;
                } else {
                    mCurrentState = State.RATIONALE;
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        State getCurrentState() {
            return mCurrentState;
        }

        void setCurrentState(State state) {
            mCurrentState = state;
        }

        void event(Event event) {
            for(Row row : mFsmTable) {
                if(row.evaluate(mCurrentState, event)) {
                    if(mDebug) {
                        String msg = String.format("event: %s, from: %s, to: %s", row.event, row.from, row.to);
                        Log.d(TAG, msg);
                    }
                    StatePair currentTransition = new StatePair(mCurrentState, row.to);
                    Runnable onTransition = mOnTransitionCallbacks.get(currentTransition);
                    if(onTransition != null) {
                        onTransition.run();
                    }
                    mCurrentState = row.to;
                    Runnable onEntry = mOnEntryCallbacks.get(mCurrentState);
                    if(onEntry != null) {
                        onEntry.run();
                    }
                    break;
                }
            }
        }

        void setDebug(boolean debug) {
            mDebug = debug;
        }

        boolean getDebug() {
            return mDebug;
        }

        private static Callable<Boolean> not(final Callable<Boolean> call) {
            return new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return !call.call();
                }
            };
        }

        @SafeVarargs
        private static Callable<Boolean> and(final Callable<Boolean>... calls) {
            return new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    for(Callable<Boolean> call : calls) {
                        if(!call.call()) {
                            return false;
                        }
                    }
                    return true;
                }
            };
        }
    }

    private Fragment mFragment;
    private Activity mActivity;
    private int mRequestCode;
    private List<String> mPermissions;
    private List<String> mDeniedPermissions;
    private boolean mIsRationaleModal = false;

    private Runnable mOnGrantedAction;
    private Runnable mOnRationaleAction;
    private Runnable mOnDeniedAction;
    private Runnable mOnDeniedPermanentlyAction;

    private Callable<Boolean> mIsPermissionGrantedGuard = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
            final Context context = getContext();
            if(context != null) {
                return hasPermissions(context, mPermissions);
            } else {
                return false;
            }
        }
    };

    private Callable<Boolean> mCanShowRationaleGuard = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
            if(mOnRationaleAction == null) {
                return false;
            }
            return any(mPermissions, new Predicate<String>() {
                @Override
                public boolean test(String permission) {
                    return ActivityCompat.shouldShowRequestPermissionRationale(mActivity, permission);
                }
            });
        }
    };

    private Callable<Boolean> mIsRationaleModalGuard = new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
            return mIsRationaleModal;
        }
    };

    private Runnable mOnRequestPermissionAction = new Runnable() {
        @Override
        public void run() {
            String[] permissionsArray = mPermissions.toArray(new String[mPermissions.size()]);
            if(mActivity != null) {
                ActivityCompat.requestPermissions(mActivity, permissionsArray, mRequestCode);
            } else if(mFragment != null) {
                mFragment.requestPermissions(permissionsArray, mRequestCode);
            }
        }
    };

    private Fsm mFsm;
    private String mExtraInstanceDebug;
    private String mExtraInstanceFsmState;

    public static Builder of(int requestCode, String[] permissions) {
        return new Builder((Activity)null, requestCode, permissions);
    }

    public static Builder of(Activity activity, int requestCode, String[] permissions) {
        return new Builder(activity, requestCode, permissions);
    }

    public static Builder of(Fragment fragment, int requestCode, String[] permissions) {
        return new Builder(fragment, requestCode, permissions);
    }

    /**
     * Create permissions helper with custom permissions request routine and custom
     * rationale check. This is indented to be used in test only.
     *
     * @param activity Activity used to request for a permissions (if non-null, fragment must be null)
     * @param fragment Fragment used to request for a permissions (if non-null, activity must be null)
     * @param requestCode Expected request code; helper will not handle results with invalid request code
     * @param permissions Permission to ask for
     * @param isRationaleModal True if rationale is modal (like a dialog), false otherwise; consult state machine diagram to see behavioral change
     * @param onGranted Called when permissions is granted
     * @param onRationale Called when rationale should be shown
     * @param onDenied Called when permissions is denied; permissions can be requested again
     * @param onDeniedPermanantly Called when permissions is denied permanently
     * @param onRequest Called when permissions should be requested; when null, default behaviour will be used
     * @param canShowRationale Should return true if rationale should be shown, false otherwise; when null, default guard will be used
     * @param isPermissionGranted Should return true if permissions is granted, false otherwise; when null, default guard will be used
     */
    private EzPermission(Activity activity,
                        Fragment fragment,
                        int requestCode,
                        boolean isRationaleModal,
                        String[] permissions,
                        Runnable onGranted,
                        Runnable onRationale,
                        Runnable onDenied,
                        Runnable onDeniedPermanantly,
                        Runnable onRequest,
                        Callable<Boolean> canShowRationale,
                        Callable<Boolean> isPermissionGranted) {

        mExtraInstanceDebug = createExtraKey(EXTRA_INSTANCE_STATE_DEBUG, permissions);
        mExtraInstanceFsmState = createExtraKey(EXTRA_INSTANCE_STATE_FSM_STATE, permissions);

        if(onRequest != null) {
            mOnRequestPermissionAction = onRequest;
        }
        if(canShowRationale != null) {
            mCanShowRationaleGuard = canShowRationale;
        }
        if(isPermissionGranted != null) {
            mIsPermissionGrantedGuard = isPermissionGranted;
        }

        if(activity != null && fragment != null) {
            throw new IllegalArgumentException("Only one fragment or activity is permitted");
        }
        mActivity = activity;
        mFragment = fragment;

        mRequestCode = requestCode;
        mPermissions = Arrays.asList(permissions);
        mIsRationaleModal = isRationaleModal;

        mOnGrantedAction = onGranted;
        mOnRationaleAction = onRationale;
        mOnDeniedAction = onDenied;
        mOnDeniedPermanentlyAction = onDeniedPermanantly;

        mFsm = new Fsm(
                mIsPermissionGrantedGuard,
                mCanShowRationaleGuard,
                mIsRationaleModalGuard,
                mOnGrantedAction,
                mOnRationaleAction,
                mOnRequestPermissionAction,
                mOnDeniedAction,
                mOnDeniedPermanentlyAction
        );

        mFsm.start();
    }

    /**
     * Save instance state to a provided bundle. It will use extras key with
     * full class name, so there should be no key name conflict.
     *
     * @param outState Bundle to save state to.
     */
    public void saveInstanceState(Bundle outState) {
        if(outState != null) {
            outState.putBoolean(mExtraInstanceDebug, mFsm.getDebug());
            outState.putSerializable(mExtraInstanceFsmState, mFsm.getCurrentState());
        }
    }

    /**
     * Restore instance state from provided bundle. If no state has been saved,
     * this call has no effect.
     * @param inState Input bundle with saved instance state.
     */
    public void restoreInstanceState(Bundle inState) {
        if(inState != null) {
            if (inState.containsKey(mExtraInstanceDebug)) {
                boolean debug = inState.getBoolean(mExtraInstanceDebug, false);
                mFsm.setDebug(debug);
            }

            if (inState.containsKey(mExtraInstanceFsmState)) {
                Fsm.State state = (Fsm.State) inState.getSerializable(mExtraInstanceFsmState);
                if (state == null) {
                    throw new IllegalStateException("Saved state machine instance state is null");
                }
                mFsm.setCurrentState(state);
            }
        }
    }

    /**
     * Set debug flag. In debug mode, state machine transitions are printed using {@link Log}.
     * By default debug is set to false.
     *
     * @param debug true to enable state machine debug log, false to disable
     */
    public void setDebug(boolean debug) {
        mFsm.setDebug(debug);
    }

    /**
     * Get current state machine debug flag
     *
     * @return Debug flag
     */
    public boolean getDebug() {
        return mFsm.getDebug();
    }

    /**
     * Call code that requires permission. If permission is not granted,
     * the state machine will handle the flow to request the permission.
     */
    public void call() {
        mFsm.event(Fsm.Event.DISPATCH);
    }

    /**
     * Notify the state machine that rationale has been accepted. It must be called
     * when user accepts rationale (ex. when user taps Ok in rationale dialog).
     */
    public void acceptRationale() {
        call();
    }

    /**
     * Notify the state machine that rationale has been rejected. It must be called
     * when user rejects rationale (ex. when user taps Cancel in rationale dialog).
     */
    public void rejectRationale() {
        mFsm.event(Fsm.Event.REJECT);
    }

    /**
     * This methods should be called in {@link Activity#onRequestPermissionsResult(int, String[], int[])}.
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if(requestCode != mRequestCode) {
            return;
        }

        boolean allGranted = true;
        mDeniedPermissions = new ArrayList<>(mPermissions.size());
        for(String permission : mPermissions) {
            if(!isGranted(permissions, grantResults, permission)) {
                allGranted = false;
                mDeniedPermissions.add(permission);
            }
        }
        
        if(allGranted) {
            mFsm.event(Fsm.Event.GRANTED);
        } else {
            mFsm.event(Fsm.Event.DENIED);
        }
    }

    /**
     * Get list of denied permissions. Result is valid only after {@link #onRequestPermissionsResult(int, String[], int[])}
     * is called. This method can be used in denied permissions callbacks.
     *
     * @return Unmodifiable list of denied permissions.
     */
    public List<String> getDeniedPermissions() {
        return  Collections.unmodifiableList(mDeniedPermissions);
    }

    /**
     * Toggle between modal and modeless rationale. This flag will change
     * internal state machine flow.
     *
     * @param isModal True if rationale is modal (dialog), false if modeless ("full-screen")
     */
    public void setIsModalRationale(boolean isModal) {
        mIsRationaleModal = isModal;
    }

    /**
     * Get current state machine state. This method is used only for tests.
     *
     * @return Current staet machine state
     */
    Fsm.State getCurrentState() {
        return mFsm.getCurrentState();
    }

    /**
     * Get context from provided {@link Activity} or {@link Fragment}. If fragment is detached,
     * context is null.
     *
     * @return Context or null, if context is not available
     */
    public Context getContext() {
        if(mActivity != null) {
            return mActivity;
        } else if(mFragment != null) {
            return mFragment.getContext();
        } else {
            return null;
        }
    }

    private static String createExtraKey(String keyPrefix, String[] permissions) {
        StringBuilder builder = new StringBuilder();
        for(String permission : permissions) {
            builder.append(permission).append(';');
        }
        return String.format("%s:%s", keyPrefix, builder);
    }

    /**
     *  Launch system settings with application configuration. This method
     *  should be used to allow user to set app permissions.
     *
     * @param context Context used to launch settings activity
     */
    public static void launchApplicationDetailsSettings(Context context) {
        final Intent i = new Intent();
        i.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.setData(Uri.parse("package:" + context.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(i);
    }

    /**
     * Checks if all permissions are granted.
     *
     * @param context Context used to access permissions API
     * @param permissions Collection of required permissions
     * @return true if all permissions are granted, false if any permission is denied
     */
    public static boolean hasPermissions(final Context context, final Collection<String> permissions) {
        return all(permissions, new Predicate<String>() {
            @Override
            public boolean test(String permission) {
                int result = ContextCompat.checkSelfPermission(context, permission);
                return result == PackageManager.PERMISSION_GRANTED;
            }
        });
    }

    /**
     * This method is used to evaluate permissions result. It scans permissions result to see if
     * requested permission has been granted.
     *
     * @param permissions List of requested permissions
     * @param grantResults Grant results that match requested permissions
     * @param requiredPermission Permission to check
     * @return true if permission is granted, false if not or permission was not requested
     */
    private static boolean isGranted(String[] permissions, int[] grantResults, String requiredPermission) {
        if(requiredPermission == null || permissions == null || grantResults == null) {
            throw new IllegalArgumentException("Permission, requested permissions and grant result cannot be null");
        }

        if(permissions.length != grantResults.length) {
            throw new IllegalArgumentException("Permissions and grant result size differ");
        }

        for(int i = 0; i < permissions.length; i++) {
            boolean isRequired = requiredPermission.equals(permissions[i]);
            boolean isGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            if(isRequired && isGranted) return true;
        }

        return false;
    }

    private static <T> Boolean all(Collection<T> collection, Predicate<T> predicate) {
        for(T item : collection) {
            if(!predicate.test(item)) return false;
        }
        return true;
    }

    private static <T> Boolean any(Collection<T> collection, Predicate<T> predicate) {
        for(T item : collection) {
            if(predicate.test(item)) return true;
        }
        return false;
    }
}
