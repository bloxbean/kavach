# Kavach

Read and follow the shared repository guidance:

@AGENTS.md

Architecture reference: [ADR-001](adr/adr-001-kavach-programmable-smart-account-architecture.md).

Use JuLC for Cardano contracts, Gradle for builds, and `com.bloxbean.cardano.kavach` as the Java package root and Gradle group. The ADR remains proposed; implementation and validation status must be reported accurately.

Safety and correctness take priority. Kavach is a language-independent protocol; JuLC is its first implementation, with future Aiken or other implementations governed by the same specification and conformance suite.
