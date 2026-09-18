/**
 * Deliberately wrong classes for {@code ArchitectureRulesBiteTest}. They sit outside
 * the application package so Spring Modulith never treats them as a module, and
 * they are test sources so the production rule checks never import them.
 */
package lk.coopfed.archfixtures;
