package com.droidbridge.root;

/** Minimal identity probe executed inside the Shizuku UserService process. */
interface IShizukuProbe {
    int getUid();
}
