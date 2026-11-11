import { Capacitor } from '@capacitor/core';
import { IS_ANDROID_WEB_VIEW } from './is-android-web-view';

/**
 * Whether running in a native Capacitor context (iOS or Android).
 * This can be used in constants that are evaluated at module load time.
 *
 * Deliberately NOT including IS_ANDROID_WEB_VIEW: a plain Android WebView
 * (e.g. the headless background boot) has the SUPAndroid bridge but no
 * Capacitor bridge, so CapacitorHttp and the other Capacitor plugins do not
 * exist there. Claiming "native" made the sync providers take the
 * CapacitorHttp path and fail; the WebView must use fetch like the web app.
 */
export const IS_NATIVE_PLATFORM = Capacitor.isNativePlatform();

/**
 * Whether running on iOS native (Capacitor).
 */
export const IS_IOS_NATIVE = Capacitor.getPlatform() === 'ios';

/**
 * Whether running on Android native (Capacitor or WebView).
 */
export const IS_ANDROID_NATIVE =
  Capacitor.getPlatform() === 'android' || IS_ANDROID_WEB_VIEW;
