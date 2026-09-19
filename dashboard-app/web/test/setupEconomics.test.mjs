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
    'Publisher-owned', 'Permanently locked', 'withdrawal unsupported',
    'not a successful transaction charge', '10 transactions', '6 reference scripts',
    'publisher-wallet', 'Pre-genesis setup cannot yet resume', 'excludes the reserved identity seed',
    'supports reference outputs', 'reference-script fees', 'Ordinary wallet coin selection']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('<details'), 'Costs and fee must be visible before signing');
});

test('unknown fee is not zero and exact repair capital stays separate', () => {
  const html = render({ publication: { capital: '1300001', scriptHash: 'expected-script', publisherAddress: 'replacement-publisher' } });
  for (const expected of ['Network fee calculated after account approvals', '1.300001 ADA',
    'expected-script', 'replacement-publisher', 'Publication grants no account authority',
    'real-wallet compatibility is not established']) assert.ok(html.includes(expected), expected);
  assert.ok(!html.includes('network fee: 0'));
});

const referenceSource = await readFile(new URL('../src/ReferenceAvailability.tsx', import.meta.url), 'utf8');
const referenceCompiled = ts.transpileModule(referenceSource, { compilerOptions: {
  jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
} }).outputText.replace("'./api'", JSON.stringify(new URL('../src/api.ts', import.meta.url).href));
const { ReferenceAvailability } = await import(`data:text/javascript;base64,${Buffer.from(
  `import React from ${JSON.stringify(import.meta.resolve('react'))};\n${referenceCompiled}`
).toString('base64')}`);

test('missing genesis-only copy never asks for paid repair, while active scripts do', () => {
  const renderReferences = references => renderToStaticMarkup(React.createElement(ReferenceAvailability,
    { references, busy: false, onRepair: () => assert.fail('Rendering cannot publish a script'), onReclaim: () => assert.fail('Rendering cannot reclaim') }));
  const optional = renderReferences([{ scriptHash: 'nft', available: false, hosting: 'missing',
    activeRequired: false, requiredFor: 'genesis only' }]);
  assert.ok(optional.includes('Optional genesis-only copy'));
  assert.ok(optional.includes('exact script bytes backed up'));
  assert.ok(!optional.includes('publication required'));
  assert.ok(!optional.includes('<button'));
  const active = renderReferences([{ scriptHash: 'asset', available: false, hosting: 'missing',
    activeRequired: true, requiredFor: 'transfers' }]);
  assert.ok(active.includes('Missing · publication required'));
  assert.ok(active.includes('Repair reference'));
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

test('vault and historical hosting remain distinct and only available vaults offer reclaim', () => {
  const html = renderToStaticMarkup(React.createElement(ReferenceAvailability, {
    references: [
      {scriptHash: 'vault-script', hosting: 'vault', available: true, reclaimable: true, activeRequired: false,
       transactionHash: 'vault-output', outputIndex: 0, hostingAddress: 'vault-holding', publisherAddress: 'owner-wallet', capital: '3000000'},
      {scriptHash: 'legacy-script', hosting: 'legacy', available: true, activeRequired: true},
      {scriptHash: 'key-script', hosting: 'publisher', available: true, activeRequired: true},
    ], busy: false, onRepair: () => {}, onReclaim: () => {},
  }));
  assert.equal((html.match(/Reclaim reference/g) || []).length, 1);
  for (const expected of ['Optional genesis-only copy', 'vault-holding', 'owner-wallet', 'vault-output#0',
    '3.00 ADA', 'historical locked reference', 'historical key hosted']) assert.ok(html.includes(expected), expected);
});
