# Project-specific R8 rules. Compose and AndroidX ship their own consumer rules.

# libtorrent4j is a SWIG binding: native code calls back into these classes by name.
-keep class org.libtorrent4j.** { *; }
