#![deny(unsafe_op_in_unsafe_fn)]

use core::ffi::c_void;

/// Increment when the Kotlin/native boundary changes incompatibly.
pub const CORE_ABI_VERSION: i32 = 1;

pub fn core_abi_version() -> i32 {
    CORE_ABI_VERSION
}

/// JNI probe used by the future Kotlin native-core adapter.
///
/// The PTY implementation remains in the existing C library in this slice;
/// this function deliberately has no side effects and is safe to ship before
/// moving any terminal-critical native code.
#[no_mangle]
pub extern "system" fn Java_com_termux_app_core_VeyTermNativeCore_nativeCoreAbiVersion(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i32 {
    core_abi_version()
}

#[cfg(test)]
mod tests {
    use super::{core_abi_version, CORE_ABI_VERSION};

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(core_abi_version(), CORE_ABI_VERSION);
    }
}
