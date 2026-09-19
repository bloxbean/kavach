# Reference-vault UI inspection

2026-09-19: Playwright inspected the actual Vite frontend at an isolated
`http://127.0.0.1:6672/` origin using intercepted synthetic API responses.
No wallet was connected, no signatures were requested, and no ledger request
or transaction was submitted. Fixture addresses and hashes are synthetic.

The desktop and 390px mobile screenshots show the exact selected output, hosted
script, separate vault/publisher addresses, ADA capital and active-reference
interruption warning. The explicit acknowledgement checkbox was checked only in
this mock workflow. Review remained disabled without a connected wallet.
The mobile document/dialog had no horizontal overflow. The dedicated browser tab
and temporary Vite server were stopped afterward; user services were untouched.

These images establish layout and mocked interaction behavior, not live wallet,
contract, ledger or mobile-browser compatibility qualification.
