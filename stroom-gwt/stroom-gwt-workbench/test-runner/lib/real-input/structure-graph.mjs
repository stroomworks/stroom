/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Real input checks for the Structure view (see index.mjs, and App/Folder/Structure). It opens on the
// graph alone: real Tab reaches the canvas, which shows its focus ring for keyboard focus, and can't
// reach the hidden table. Real arrow keys in the View options choose Both, the option showing its focus
// ring; then real Tab reaches the canvas and the table of documents under it, and the node of the row
// with the focus is ringed on the canvas, following real arrow keys. A play's Tab is simulated and can't
// show :focus-visible.

import assert from 'node:assert/strict';

const SEARCH = 'input[aria-label="Find documents in the graph"]';
const FOCUSED_NODE = '.structure-graph__node--focused';
const TABLE = '.structure-graph__table';

// Presses real Tab until the focus is in the element the selector finds, at most a few times
async function tabTo(page, selector) {
  for (let i = 0; i < 6; i++) {
    await page.keyboard.press('Tab');
    const inside = await page.evaluate((wanted) => {
      const container = document.querySelector(wanted);
      return container !== null && container.contains(document.activeElement);
    }, selector);
    if (inside) {
      return;
    }
  }
  assert.fail(`real Tab did not reach ${selector}`);
}

async function isFocusIn(page, selector) {
  return page.evaluate((wanted) => {
    const container = document.querySelector(wanted);
    return container !== null && container.contains(document.activeElement);
  }, selector);
}

async function focusedNodeId(page) {
  return page.evaluate((selector) => {
    const node = document.querySelector(selector);
    return node === null ? null : node.getAttribute('data-id');
  }, FOCUSED_NODE);
}

export const CHECKS = {
  'app-folder-structure--default': async (page) => {
    await page.locator(SEARCH).focus();
    await tabTo(page, '.structure-graph__canvas');
    assert.ok(await page.evaluate(() => document.activeElement.matches(':focus-visible')),
      'the canvas shows a focus ring when the keyboard reaches it');

    // The graph is shown alone, so the hidden table can't take the focus (WCAG 2.4.11)
    await page.keyboard.press('Tab');
    assert.equal(await isFocusIn(page, TABLE), false, 'Tab does not reach the hidden table');

    // The View options are one tab stop, and the arrow keys choose as they move: Graph to Table to Both
    await page.getByRole('radio', { name: 'Graph' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');
    assert.ok(await page.getByRole('radio', { name: 'Both' }).isChecked(), 'the arrow keys choose Both');
    assert.ok(await page.evaluate(() => document.activeElement.matches(':focus-visible')),
      'the chosen option shows a focus ring for keyboard focus');
    await page.locator(TABLE).waitFor({ state: 'visible' });

    await page.locator(SEARCH).focus();
    await tabTo(page, '.structure-graph__canvas');
    await tabTo(page, TABLE);
    const first = await focusedNodeId(page);
    assert.ok(first, 'the canvas rings the node of the table row with the focus');

    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(([selector, before]) => {
      const node = document.querySelector(selector);
      return node !== null && node.getAttribute('data-id') !== before;
    }, [FOCUSED_NODE, first]);
    assert.equal(await page.locator(FOCUSED_NODE).count(), 1, 'one node is ringed at a time');
  },
};
