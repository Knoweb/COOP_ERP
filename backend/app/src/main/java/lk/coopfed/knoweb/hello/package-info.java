/**
 * The template module (17A section 12): deliberately trivial, but it uses every kernel
 * interface and follows every convention, so that copying it ({@code make new-module})
 * gives a working skeleton. Read its README.md first.
 *
 * <p>It depends on the kernel's published {@code api} package and on nothing else.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Hello (template module)",
        allowedDependencies = {"kernel::api"})
package lk.coopfed.knoweb.hello;
