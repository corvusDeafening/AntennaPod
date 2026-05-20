# jUPnP bundles OSGi, CDI, and Jetty transport code that is never used on
# Android. None of these libraries are on the Android classpath, so tell R8
# to ignore the missing references rather than aborting the build.
-dontwarn org.osgi.**
-dontwarn javax.enterprise.**
-dontwarn javax.inject.**
-dontwarn javax.servlet.**
-dontwarn org.eclipse.jetty.**

# Preserve jUPnP service and support classes used via reflection
-keep class org.jupnp.** { *; }
-keep interface org.jupnp.** { *; }
