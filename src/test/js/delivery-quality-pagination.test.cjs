const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(
  path.join(__dirname, '../../main/resources/static/delivery-prototype.html'),
  'utf8'
);
const sectionStart = html.indexOf('// ===== 渲染：集群/质量（真实扫描） =====');
const sectionEnd = html.indexOf('function renderPreviews', sectionStart);
assert.ok(sectionStart >= 0 && sectionEnd > sectionStart, '无法定位集群健康渲染代码');

function createPage(summary) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, { innerHTML: '', textContent: '', hidden: true });
      return elements.get(id);
    }
  };
  const context = vm.createContext({
    document,
    statCard: (label, value) => `${label}:${value}`,
    pill: (kind, text) => `<span class="pill ${kind}">${text}</span>`,
    esc: value => String(value == null ? '' : value),
    emptyList: text => `<div class="empty-state">${text}</div>`
  });
  vm.runInContext(html.slice(sectionStart, sectionEnd), context);
  const render = data => vm.runInContext(`renderQuality(${JSON.stringify(data)})`, context);
  const goToPage = page => vm.runInContext(`goToQualityPage(${page})`, context);
  render(summary);
  return { document, goToPage, render };
}

function summary({ pods = 0, deployments = 0, nodes = 0 } = {}) {
  return {
    success: true,
    readyNodes: 3,
    totalNodes: 3,
    runningPods: 20,
    totalPods: 20,
    totalDeployments: deployments,
    podIssueCount: pods,
    deploymentIssueCount: deployments,
    nodeIssueCount: nodes,
    podIssues: Array.from({ length: pods }, (_, i) => ({
      namespace: 'test', name: `pod-${i}`, phase: 'Failed', restarts: 1
    })),
    deploymentIssues: Array.from({ length: deployments }, (_, i) => ({
      namespace: 'test', name: `workload-${i}`, singleReplica: true
    })),
    nodeIssues: Array.from({ length: nodes }, (_, i) => ({ name: `node-${i}`, ready: false }))
  };
}

function names(page) {
  return page.document.getElementById('qualityIssueList').innerHTML.match(/(?:pod|workload|node)-\d+/g) || [];
}

function rowCount(page) {
  return (page.document.getElementById('qualityIssueList').innerHTML.match(/class="quality-item"/g) || []).length;
}

function pagerText(page) {
  return page.document.getElementById('qualityIssuePagination').innerHTML;
}

test('健康问题每页展示 10 条，并可访问全部被截断的 Workload 问题', () => {
  const page = createPage(summary({ pods: 2, deployments: 20, nodes: 1 }));
  assert.equal(rowCount(page), 10);
  assert.match(pagerText(page), /第 1-10 条 \/ 共 23 条/);
  assert.match(pagerText(page), /data-quality-page="3"/);
  assert.match(pagerText(page), /aria-label="上一页"[^>]*disabled/);
  assert.match(page.document.getElementById('qualityStats').innerHTML, /健康问题:23/);

  const allNames = [...names(page)];
  page.goToPage(2);
  assert.equal(rowCount(page), 10);
  assert.match(pagerText(page), /第 11-20 条 \/ 共 23 条/);
  allNames.push(...names(page));
  page.goToPage(3);
  assert.equal(rowCount(page), 3);
  assert.match(pagerText(page), /第 21-23 条 \/ 共 23 条/);
  assert.match(pagerText(page), /aria-label="下一页"[^>]*disabled/);
  allNames.push(...names(page));
  assert.equal(allNames.length, 23);
  assert.ok(allNames.includes('workload-19'));
  assert.ok(allNames.includes('node-0'));
});

test('页码导航限制在有效范围内，并标记当前页', () => {
  const page = createPage(summary({ deployments: 23 }));
  page.goToPage(2);
  assert.match(pagerText(page), /data-quality-page="2" aria-current="page"/);
  page.goToPage(99);
  assert.match(pagerText(page), /第 21-23 条 \/ 共 23 条/);
  page.goToPage(0);
  assert.match(pagerText(page), /第 1-10 条 \/ 共 23 条/);
  assert.match(pagerText(page), /aria-label="上一页"[^>]*disabled/);
});

test('重新扫描后回到第一页', () => {
  const data = summary({ deployments: 23 });
  const page = createPage(data);
  page.goToPage(2);
  page.render(data);
  assert.match(pagerText(page), /第 1-10 条 \/ 共 23 条/);
  assert.deepEqual(names(page).slice(0, 2), ['workload-0', 'workload-1']);
});

test('空结果和扫描失败时清空列表并隐藏分页', () => {
  const page = createPage(summary({ deployments: 23 }));
  page.goToPage(2);
  page.render(summary());
  assert.match(page.document.getElementById('qualityIssueList').innerHTML, /集群扫描无异常/);
  assert.equal(page.document.getElementById('qualityIssuePagination').hidden, true);

  page.render(summary({ deployments: 23 }));
  page.render(null);
  assert.match(page.document.getElementById('qualityIssueList').innerHTML, /集群不可达/);
  assert.equal(page.document.getElementById('qualityIssuePagination').hidden, true);
});
