import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Render the real component using the already pinned TypeScript compiler.
const source = await readFile(new URL('../src/SetupEconomics.tsx', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: {
  jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
} }).outputText.replace("'./api'", JSON.stringify(new URL('../src/api.ts', import.meta.url).href));
const moduleSource = `import React from ${JSON.stringify(import.meta.resolve('react'))};\n${compiled}`;
const { SetupEconomics } = await import(`data:text/javascript;base64,${Buffer.from(moduleSource).toString('base64')}`);
const render = plan => renderToStaticMarkup(React.createElement(SetupEconomics, { plan }));

test('quote separates ownership, irreversible reserve, collateral and actual fee before signing', () => {
  const html = render({ costs: { referenceCapital: '20123456', stateReserve: '4000000',
    registrationReserve: '6000000', feeAllowance: '10000000', collateralReserve: '5000000',
    totalFundingEstimate: '45123456', publisherAddress: 'publisher-wallet', referenceCount: 6,
    setupTransactions: 10 }, fee: '1122225' });
  for (const expected of ['20.123456 ADA', '4.00 ADA', '45.123456 ADA', '1.122225 ADA',
    'claimable later by the publisher wallet', 'permanently locked', 'withdrawal unsupported',
    'not a successful transaction charge', '10 transactions', '6 reference scripts',
    'publisher-wallet', 'only this publisher wallet', 'recovery guardians can’t claim them',
    'any time after setup', 'account pauses', 'publisher key is lost',
    'pre-genesis setup cannot yet resume', 'excludes the reserved identity seed',
    'supports reference outputs', 'reference-script fees', 'ordinary wallet coin selection']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('<details'), 'Costs and fee must be visible before signing');
});

test('unknown fee is not zero and exact repair capital stays separate', () => {
  const html = render({ publication: { capital: '1300001', scriptHash: 'expected-script', publisherAddress: 'replacement-publisher' } });
  for (const expected of ['Network fee calculated after account approvals', '1.300001 ADA',
    'expected-script', 'replacement-publisher', 'Publication grants no account authority',
    'real-wallet compatibility is not established']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('network fee: 0'));
});

const depositsSource = await readFile(new URL('../src/DepositsPanel.tsx', import.meta.url), 'utf8');
const depositsCompiled = ts.transpileModule(depositsSource, { compilerOptions: {
  jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
} }).outputText.replace("'./api'", JSON.stringify(new URL('../src/api.ts', import.meta.url).href));
const { DepositsPanel } = await import(`data:text/javascript;base64,${Buffer.from(
  `import React from ${JSON.stringify(import.meta.resolve('react'))};\n${depositsCompiled}`
).toString('base64')}`);
const renderDeposits = (references, deposits) => renderToStaticMarkup(React.createElement(DepositsPanel,
  { references, deposits, busy: false, onRepair: () => assert.fail('Rendering cannot publish a script'), onReclaim: () => assert.fail('Rendering cannot reclaim') }));

test('missing genesis-only copy never asks for paid repair, while active scripts do', () => {
  const optional = renderDeposits([{ scriptHash: 'nft', available: false, hosting: 'missing',
    activeRequired: false, requiredFor: 'genesis only' }]);
  assert.ok(optional.includes('Not needed after account creation'));
  assert.ok(optional.includes('exact script bytes'));
  assert.ok(!optional.includes('Missing'));
  assert.ok(!optional.includes('<button'));
  const active = renderDeposits([{ scriptHash: 'asset', available: false, hosting: 'missing',
    activeRequired: true, requiredFor: 'transfers' }]);
  assert.ok(active.includes('Missing · account paused until republished'));
  assert.ok(active.includes('>Republish</button>'));
  assert.ok(active.includes('role="alert"'), 'A paused account must be flagged prominently');
});

test('datum growth requires visible permanent sponsor top-up disclosure before signing', () => {
  const html = render({ fee: '1200000', stateFunding: {
    previousReserve: '2345678', nextReserve: '3345678', topUp: '1000000',
  } });
  for (const expected of ['Permanent account state funding', '2.345678 ADA', '3.345678 ADA',
    'Additional sponsor funding', '1.00 ADA', '1.2 ADA', 'fee-paying wallet supplies this top-up',
    'permanently locked', 'reserve withdrawal are unsupported']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('<details'), 'Permanent top-up must be visible without expanding review');
});


test('vault reclaim review displays exact output, full return and distinct holding and publisher addresses', () => {
  const html = render({ fee: '612345', reclamation: {
    transactionHash: 'exact-output', outputIndex: 2, scriptHash: 'hosted-script',
    hostingAddress: 'vault-address', publisherAddress: 'publisher-return', returnedCapital: '30234567', activeRequired: true,
    returnedAssets: [{unit:'returned-native-asset',quantity:'9007199254740993'}],
  } });
  for (const expected of ['exact-output#2', 'hosted-script', 'vault-address', 'publisher-return',
    '30.234567 ADA', '0.612345 ADA', 'funded separately', 'removes an active reference',
    'No replacement is created automatically', 'returned-native-asset', '9007199254740993 units']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('<details'));
});

test('vault and historical hosting remain distinct and only available vaults offer a claim', () => {
  const html = renderDeposits([
    {scriptHash: 'vault-script', hosting: 'vault', available: true, reclaimable: true, activeRequired: false, label: 'Account identity (NFT) policy',
     transactionHash: 'vault-output', outputIndex: 0, hostingAddress: 'vault-holding', publisherAddress: 'owner-wallet', capital: '3000000'},
    {scriptHash: 'other-vault', hosting: 'vault', available: true, reclaimable: false, activeRequired: true, capital: '1000000'},
    {scriptHash: 'legacy-script', hosting: 'legacy', available: true, activeRequired: true, capital: '80000000'},
    {scriptHash: 'key-script', hosting: 'publisher', available: true, activeRequired: true, capital: '26000000'},
  ], { stateReserve: '2762710', registrationDeposit: '2000000', registeredScripts: 2 });
  assert.equal((html.match(/>Claim<\/button>/g) || []).length, 1);
  for (const expected of ['Account identity (NFT) policy', 'owner-wallet', 'vault-output#0', '3.00 ADA',
    'Claimable by the publisher wallet', 'Locked permanently (older setup)', 'publisher’s wallet address (older setup)',
    'Held in a vault for a different account', 'this dashboard can’t claim them']) assert.ok(html.includes(expected), expected);
});

test('deposit summary states who can claim, when, and every permanently locked amount', () => {
  const html = renderDeposits([
    {scriptHash: 'a'.repeat(56), hosting: 'vault', available: true, reclaimable: true, activeRequired: true, label: 'Signing module',
     transactionHash: 'b'.repeat(64), outputIndex: 1, hostingAddress: 'vault-holding', publisherAddress: 'owner-wallet', capital: '64530000'},
    {scriptHash: 'c'.repeat(56), hosting: 'vault', available: true, reclaimable: true, activeRequired: true, label: 'Account state validator',
     transactionHash: 'd'.repeat(64), outputIndex: 0, hostingAddress: 'vault-holding', publisherAddress: 'owner-wallet', capital: '35090000'},
    {scriptHash: 'e'.repeat(56), hosting: 'legacy', available: true, activeRequired: true, capital: '80000000'},
  ], { stateReserve: '2762710', registrationDeposit: '2000000', registeredScripts: 2 });
  for (const expected of ['99.62 ADA', '86.76271 ADA', '2.76271 ADA', '4.00 ADA', '80.00 ADA',
    'Only the wallet that paid to publish these scripts', 'owner-wallet',
    'recovery guardians can’t claim these deposits', 'never moves the ADA or tokens in your account',
    'Any time. There is no waiting period.', 'account pauses until that script is published again',
    'Account state reserve', 'Stake registration deposits', '2 × 2.00 ADA', 'Older reference copies',
    'publisher wallet’s key is lost']) assert.ok(html.includes(expected), expected);
  assert.equal((html.match(/owner-wallet/g) || []).length, 1, 'Each publisher wallet is listed once');
  assert.equal((html.match(/>Claim<\/button>/g) || []).length, 2);
});

test('locked amounts are never shown as zero when the backend did not report them', () => {
  const html = renderDeposits([{scriptHash: 'x', hosting: 'missing', available: false, activeRequired: true}]);
  assert.ok(html.includes('Unknown'));
  assert.ok(html.includes('Refresh the account to load its locked reserve amounts'));
});
