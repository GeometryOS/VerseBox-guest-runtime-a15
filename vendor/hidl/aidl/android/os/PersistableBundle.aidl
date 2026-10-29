/*
 * Local declaration of the framework parcelable that android.hardware.vibrator's
 * VendorEffect refers to.
 *
 * AOSP ships it as the "PersistableBundle_aidl" header module (a prebuilt AIDL
 * header from frameworks/base); that tree is not part of this workspace, so the
 * declaration is kept here. It is a declaration only: this project never
 * marshals a PersistableBundle (performVendorEffect is implemented as
 * UNSUPPORTED_OPERATION and no vendor effect is ever sent).
 */
package android.os;

@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable PersistableBundle {
}
