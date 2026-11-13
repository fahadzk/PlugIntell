import {readFileSync} from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../../main/resources/response-ui/renderers.js',import.meta.url),'utf8');
const {tableCellText}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
test('empty database values remain visible in table cells',()=>{
 assert.equal(tableCellText(null),'(null)');
 assert.equal(tableCellText(undefined),'(missing)');
 assert.equal(tableCellText([]),'(empty list)');
 assert.equal(tableCellText('  '),'(empty text)');
});
test('zero, false and populated collections retain their values',()=>{
 assert.equal(tableCellText(0),'0');assert.equal(tableCellText(false),'false');
 assert.equal(tableCellText(['Lahore','Multan']),'["Lahore","Multan"]');
});
