// Run with: node --test frontend/tests/ui-regression.test.cjs
// Exercises the shipped inline controller against the existing backend contracts.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = readFileSync(path.join(__dirname, '../index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function setup(responder = () => ({})) {
    const elements = new Map();
    const timers = new Map();
    const requests = [];
    let timerId = 0;
    const element = (id = '') => {
        const classes = new Set();
        return { id, value: '', textContent: '', innerHTML: '', disabled: false,
            style: {}, children: [], options: [], listeners: {}, scrollHeight: 100,
            appendChild(child) { this.children.push(child); },
            addEventListener(name, fn) { this.listeners[name] = fn; },
            classList: {
                toggle(name, on) {
                    if (on === undefined) {
                        if (classes.has(name)) classes.delete(name); else classes.add(name);
                    } else {
                        on ? classes.add(name) : classes.delete(name);
                    }
                    return classes.has(name);
                },
                contains(name) { return classes.has(name); },
                add(name) { classes.add(name); },
                remove(name) { classes.delete(name); }
            },
        };
    };
    for (const [, id] of html.matchAll(/\bid="([^"]+)"/g)) elements.set(id, element(id));
    for (const [id, value] of Object.entries({ 'qa-topk-select': '5', 'vector-topk-select': '5', 'ram-capacity-select': '8', 'cpu-cores-select': '8', 'os-mode-select': 'AUTO' })) elements.get(id).value = value;
    elements.get('hw-preset-select').options = ['8_4','8_8','16_8','16_16','32_16','custom'].map(value => ({value}));
    const tabs = [...html.matchAll(/<button class="tab-button[^\"]*" onclick="([^"]+)"/g)].map(([, handler]) => ({...element(), getAttribute: () => handler}));
    const panels = [...elements.values()].filter(e => /^tab-/.test(e.id));
    const domReady = [];
    const context = vm.createContext({
        console, FormData, window: { location: { protocol: 'http:' } },
        setTimeout: fn => { domReady.push(fn); },
        setInterval: fn => { timers.set(++timerId, fn); return timerId; },
        clearInterval: id => timers.delete(id),
        document: { getElementById: id => elements.get(id), createElement: () => element(),
            querySelectorAll: selector => selector === '.tab-button' ? tabs : panels,
            addEventListener: (name, fn) => { if (name === 'DOMContentLoaded') domReady.push(fn); } },
        fetch: async (url, options = {}) => {
            const request = { url, method: options.method || 'GET', body: options.body };
            requests.push(request);
            const result = await responder(request);
            return { ok: !result?.__status || result.__status < 400, status: result?.__status || 200, json: async () => result || {} };
        },
    });
    vm.runInContext(script, context);
    return { context, elements, requests, timers, domReady, tabs, panels,
        run: expression => vm.runInContext(expression, context),
        el: id => elements.get(id),
    };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
const profile = { ramCapacityGb: 8, allocatedCores: 8, memorySafetyCeilingMb: 6144, heapUsedMb: 128, heapMaxMb: 2048 };

test('both shipped frontends and layout styles stay identical', () => {
    assert.equal(html, readFileSync(path.join(__dirname, '../../src/main/resources/static/index.html'), 'utf8'));
    assert.equal(readFileSync(path.join(__dirname, '../css/layout.css'), 'utf8'), readFileSync(path.join(__dirname, '../../src/main/resources/static/css/layout.css'), 'utf8'));
    const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
    assert.equal(ids.length, new Set(ids).size);
    assert.equal((html.match(/<button\b/g) || []).length, 27);
    assert.equal((html.match(/<input\b/g) || []).length, 6);
    assert.equal((html.match(/<select\b/g) || []).length, 6);
});

test('system information uses backend names and converts MB to GB', async () => {
    const h = setup(() => ({ detectedOs:'MACOS', activeMode:'LINUX', osName:'Mac OS X', osArch:'aarch64', physicalMemoryMb:8192, cpuCores:8, javaVersion:'17', gpuInfo:{name:'Apple GPU'} }));
    await h.run('loadSystemInfo()');
    assert.equal(h.el('sys-os').textContent, 'MACOS (Mac OS X)');
    assert.equal(h.el('sys-arch').textContent, 'aarch64');
    assert.equal(h.el('sys-ram').textContent, 8);
    assert.equal(h.el('sys-gpu').textContent, 'Apple GPU');
    assert.equal(h.el('os-mode-select').value, 'LINUX');
    await h.run("setOsOverride('AUTO')");
    assert.ok(h.requests.some(r => r.url === '/api/rag/os-override?mode=AUTO' && r.method === 'POST'));
});

test('hardware presets, individual selectors, apply, and sweep keep their actions', async () => {
    const h = setup(({url}) => url.includes('hardware-tuning') ? profile : {});
    h.run("onPresetChange('16_16')"); await flush();
    assert.ok(h.requests.some(r => r.url === '/api/rag/hardware-tuning?ramCapacityGb=16&allocatedCores=16' && r.method === 'POST'));
    h.el('ram-capacity-select').value = '32'; h.el('cpu-cores-select').value = '12';
    h.run('onManualHwChange()'); await flush();
    assert.ok(h.requests.some(r => r.url === '/api/rag/hardware-tuning?ramCapacityGb=32&allocatedCores=12'));
    await h.run('applyHardwareProfile()');
    await h.run('runGcSweep()'); await flush();
    assert.ok(h.requests.some(r => r.url === '/api/rag/gc' && r.method === 'POST'));
});

test('all navigation tabs still select exactly one panel and load relevant data', async () => {
    const h = setup(({url}) => /documents|jobs/.test(url) ? [] : {chromaOnline:true});
    for (const id of ['tab-qa','tab-pipeline','tab-vectordb','tab-search','tab-jobs','tab-settings']) {
        h.run(`activateTab('${id}')`); await flush();
        assert.deepEqual(h.panels.filter(p => p.classList.contains('active')).map(p => p.id), [id]);
        assert.equal(h.tabs.filter(t => t.classList.contains('active')).length, 1);
    }
    assert.ok(h.requests.some(r => r.url === '/api/rag/jobs'));
    assert.ok(h.requests.some(r => r.url === '/api/rag/vector-db/documents?limit=50'));
});

test('questions, all suggestion chips, top-K and citations retain their request contract', async () => {
    const h = setup(() => ({ answer: 'A cited answer', citations:[{document:'test.pdf',page:2,heading:'Test',similarityScore:.9,preview:'Excerpt'}] }));
    h.el('qa-query-input').value = 'question & answer'; h.el('qa-topk-select').value = '10';
    await h.run('sendQuestion()');
    assert.equal(h.requests[0].url, '/api/rag/ask?query=question%20%26%20answer&topK=10');
    assert.equal(h.requests[0].method, 'POST');
    assert.match(h.el('chat-stream').children[1].innerHTML, /test.pdf.*p.2/s);
    assert.equal(h.el('btn-submit-qa').disabled, false);
    for (const [, query] of html.matchAll(/onclick="fillQuery\('([^']+)'\)"/g)) {
        h.run(`fillQuery(${JSON.stringify(query)})`); await flush();
        assert.ok(h.requests.some(r => r.url.includes(encodeURIComponent(query))));
    }
    const before = h.requests.length; await h.run('sendQuestion()');
    assert.equal(h.requests.length, before, 'empty question does not submit');
});

test('pipeline status follows actual stage snapshots and stops polling when complete', async () => {
    let status = {runId:'run-test',isRunning:true,currentStage:'EMBED',discoveredDocs:3,processedDocs:2,failedDocs:1,totalPages:12,totalChunks:8,indexedVectors:0,resourceState:'WARNING'};
    const h = setup(() => status);
    await h.run('fetchPipelineStatus()');
    assert.match(h.el('run-id-badge').textContent, /run-test/);
    assert.equal(h.el('pipeline-progress-bar').style.width, '67%');
    assert.match(h.el('pipeline-progress-label').textContent, /EMBED.*2\/3 docs parsed.*1 failed/);
    assert.equal(h.el('stage-embed-act').textContent, 'Running…');
    assert.match(h.el('stage-chunk-ok').textContent, /8/);
    assert.equal(h.el('failed-docs-list').style.display, 'block');
    assert.equal(h.timers.size, 1);
    status = {...status,isRunning:false,currentStage:'COMPLETED',indexedVectors:8};
    await h.run('fetchPipelineStatus()');
    assert.equal(h.el('pipeline-progress-bar').style.width, '100%');
    assert.equal(h.timers.size, 0);
});

test('full pipeline, individual stages, path and resume preserve endpoint actions', async () => {
    const h = setup(() => ({ totalExecutionTimeMs:123,parsing:{processed:2,totalPages:12,pagesPerSecond:24},chunking:{totalChunks:8} }));
    h.el('pipeline-folder-path').value = '/tmp/example folder';
    for (const endpoint of ['ingest','parse-only','chunk-only','vectorize-only']) {
        await h.run(`triggerPipeline('/api/rag/${endpoint}')`);
        assert.ok(h.requests.some(r => r.url === `/api/rag/${endpoint}?folderPath=%2Ftmp%2Fexample%20folder` && r.method === 'POST'));
    }
    assert.equal(h.el('kpi-pages').textContent, 12);
    assert.equal(h.el('kpi-chunks').textContent, 8);
    await h.run('resumePipeline()');
    assert.ok(h.requests.some(r => r.url === '/api/rag/pipeline/resume' && r.method === 'POST'));
});

test('file inspection uses POST and renders the actual metadata contract', async () => {
    const h = setup(() => ({filePath:'/tmp/test.pdf',sizeMb:2.5,recommendedPageBatchSize:10,strategy:'STANDARD_STREAMING'}));
    h.el('inspect-file-path').value = '/tmp/test.pdf';
    await h.run('runInspectFile()');
    assert.equal(h.requests[0].method, 'POST');
    assert.equal(h.requests[0].url, '/api/rag/inspect-file?filePath=%2Ftmp%2Ftest.pdf');
    assert.match(h.el('inspect-result-box').innerHTML, /2.50 MB/);
    assert.match(h.el('inspect-result-box').innerHTML, /STANDARD_STREAMING/);
});

test('upload selection filters PDFs, submits multipart files and clears selection on success', async () => {
    const h = setup(() => ({totalExecutionTimeMs:10}));
    h.context.files = [new File(['sample'], 'sample.pdf', {type:'application/pdf'}),new File(['text'], 'notes.txt')];
    h.run('handleFilesSelected(files)');
    assert.equal(h.el('selected-files-count').textContent, 1);
    assert.equal(h.el('btn-upload-ingest').style.display, 'inline-flex');
    await h.run('uploadAndRunPipeline()');
    const req = h.requests.find(r => r.url === '/api/rag/upload-and-ingest');
    assert.equal(req.method, 'POST');
    assert.equal(req.body.getAll('files').length, 1);
    assert.equal(req.body.get('files').name, 'sample.pdf');
    assert.equal(h.el('btn-upload-ingest').style.display, 'none');
    assert.equal(h.el('selected-files-count').textContent, '0');
    assert.equal(h.el('btn-upload-ingest').disabled, false);
});

test('drop zone still selects dropped PDFs through the same file selection handler', () => {
    const h = setup();
    h.domReady[1](); // the existing deferred drag-and-drop registration
    h.el('drop-zone').listeners.drop({preventDefault(){},stopPropagation(){},dataTransfer:{files:[{name:'dropped.PDF',size:128}]}});
    assert.equal(h.el('selected-files-count').textContent, 1);
    assert.match(h.el('selected-files-list').innerHTML, /dropped.PDF/);
});

test('similarity search supports chosen top-K, results and empty states', async () => {
    let results = [{document:'test.pdf',page:2,heading:'Intro',score:.9,text:'Sample'}];
    const h = setup(() => results);
    h.el('vector-search-input').value = 'sample'; h.el('vector-topk-select').value = '3';
    await h.run('runSearch()');
    assert.equal(h.requests[0].url, '/api/rag/search?query=sample&topK=3');
    assert.match(h.el('vector-search-results').innerHTML, /Score: 90%/);
    results = []; await h.run('runSearch()');
    assert.match(h.el('vector-search-results').innerHTML, /No matching/);
});

test('HTTP failures render errors rather than false success and restore controls', async () => {
    const h = setup(() => ({__status:409,error:'Conflict',message:'Pipeline is already executing'}));
    await h.run("triggerPipeline('/api/rag/ingest')");
    assert.match(h.el('pipeline-status-text').innerHTML, /failed: Pipeline is already executing/);
    await h.run('resumePipeline()');
    assert.match(h.el('pipeline-status-text').innerHTML, /Resume failed/);
    h.el('qa-query-input').value = 'test'; await h.run('sendQuestion()');
    assert.match(h.el('chat-stream').children[1].innerHTML, /Error:/);
    assert.equal(h.el('btn-submit-qa').disabled, false);
    h.context.files = [new File(['sample'], 'sample.pdf')];
    h.run('handleFilesSelected(files)'); await h.run('uploadAndRunPipeline()');
    assert.match(h.el('pipeline-status-text').innerHTML, /Upload failed/);
    assert.equal(h.el('btn-upload-ingest').disabled, false);
    assert.equal(h.el('btn-upload-ingest').style.display, 'inline-flex');
    await h.run('loadVectorHub()');
    assert.match(h.el('vdb-table-body').innerHTML, /Error loading vectors/);
});
