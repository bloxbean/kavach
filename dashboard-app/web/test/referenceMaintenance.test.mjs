import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { requestAction } from '../src/requestAction.ts';

test('bulk republication stays separate from exact-output vault reclaim and repair', () => {
    assert.equal(requestAction('Republish references'), 'Republish references');
    assert.equal(requestAction('Reclaim reference'), 'reclaim-reference');
    assert.equal(requestAction('Repair reference'), 'repair-reference');
    assert.equal(requestAction('Create account'), 'Create account');
});

const source = await readFile(new URL('../src/RewardSinks.tsx', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: {
    jsx: ts.JsxEmit.React, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
} }).outputText;
const { RewardSinks } = await import(`data:text/javascript;base64,${Buffer.from(
    `import React from ${JSON.stringify(import.meta.resolve('react'))};\n${compiled}`
).toString('base64')}`);

test('custom immutable core and module sinks remain separately editable in creation', () => {
    const changes = [];
    const props = { coreSink: 'core-recipient', moduleSink: 'module-recipient', onChange: (...change) => changes.push(change) };
    const html = renderToStaticMarkup(React.createElement(RewardSinks, props));
    for (const expected of ['Core reward sink', 'Module reward sink', 'value="core-recipient"',
        'value="module-recipient"', 'immutable', 'Changing reference hosting does not redirect']) assert.ok(html.includes(expected), expected);
    const rendered = RewardSinks(props);
    rendered.props.children[0].props.children[1].props.onChange({ target: { value: 'new-core' } });
    rendered.props.children[1].props.children[1].props.onChange({ target: { value: 'new-module' } });
    assert.deepEqual(changes, [['coreSink', 'new-core'], ['moduleSink', 'new-module']]);
});
