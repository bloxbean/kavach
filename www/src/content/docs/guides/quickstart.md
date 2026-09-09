---
title: "Run Kavach locally"
description: "Run Kavach locally — a practical Kavach guide."
---

The website is static documentation. The working dashboard runs separately against your local Yaci DevKit.

## Prerequisites

Use the repository's pinned Java 25 and JuLC toolchain, Node.js for the dashboard, and a running Yaci DevKit with network magic **42**. The Blockfrost-compatible endpoint is `http://localhost:8080/api/v1/`; the faucet uses port 10000.

Follow the [toolchain setup](https://github.com/bloxbean/kavach/blob/main/toolchain/julc/README.md) before building. The pinned JuLC snapshot must be available locally.

## Start the backend

From the repository root:

```sh
./gradlew :dashboard-app:backend:run
```

## Start the dashboard

In a second terminal:

```sh
cd dashboard-app/web
npm ci
npm run dev
```

Open **http://127.0.0.1:6670/**. The local API uses port 8095; the dashboard proxies API requests.

## Connect the right network

Configure Yano or a compatible browser wallet for this exact DevKit ledger. A generic “testnet” selection does not distinguish DevKit from preview or preprod. Fund its change address with disposable test ADA.

Keep development services local. Do not reset DevKit while reward or delayed-recovery qualification workers are running.

The [dashboard README](https://github.com/bloxbean/kavach/blob/main/dashboard-app/README.md) is the detailed operational reference.
