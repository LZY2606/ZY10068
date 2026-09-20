let state = null;
let branchView = null;
let selectedBranch = 'main';

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || response.statusText);
  return payload;
}

function formObject(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function idempotencyKey() {
  return 'idem-' + crypto.randomUUID();
}

function signedYear(era, value) {
  if (!value) return null;
  const year = Number(value);
  return era === 'bce' ? 1 - year : year;
}

async function submitCommands(commands) {
  await api('/api/batch', {
    method: 'POST',
    body: JSON.stringify({ idempotencyKey: idempotencyKey(), commands })
  });
  await api('/api/wait-jobs', { method: 'POST' });
  await refresh();
}

async function refresh() {
  state = await api('/api/state');
  $('ruleVersion').textContent = state.ruleVersion + ' · seq ' + state.seq;
  renderBranches();
  branchView = await api('/api/branches/' + encodeURIComponent(selectedBranch));
  renderView();
  $('eventsTab').textContent = JSON.stringify(await api('/api/events'), null, 2);
}

function renderBranches() {
  const select = $('branchSelect');
  select.innerHTML = '';
  state.branches.forEach((branch) => {
    const option = document.createElement('option');
    option.value = branch.id;
    option.textContent = `${branch.name} (${branch.id}/${branch.status})`;
    if (branch.id === selectedBranch) option.selected = true;
    select.appendChild(option);
  });
  const contextOptions = branchView ? branchView.contexts : [];
  ['datingContext', 'relationFrom', 'relationTo'].forEach((id) => {
    const select = $(id);
    select.innerHTML = '';
    contextOptions.forEach((context) => {
      const option = document.createElement('option');
      option.value = context.id;
      option.textContent = `${context.id} — ${context.label}`;
      select.appendChild(option);
    });
  });
  $('snapshots').innerHTML = state.snapshots.map((snapshot) =>
    `<div class="card"><strong>${snapshot.name}</strong> <span class="muted">${snapshot.id}</span>
      <button type="button" onclick="showSnapshot('${snapshot.id}')">查看冻结视图</button><br>
      <span class="muted">${snapshot.hash}</span></div>`).join('');
}

function renderView() {
  const contradiction = branchView.contradiction;
  $('status').innerHTML = contradiction
    ? `<div class="status-bad">矛盾：${contradiction.type}</div>
       <p>${contradiction.message}</p>
       <pre>${JSON.stringify(contradiction.minimalEvidenceSets, null, 2)}</pre>`
    : '<div class="status-ok">可行：当前证据允许至少一个层序解释</div>';
  $('topology').innerHTML = (branchView.stableTopologicalOrder || []).map((id) => `<li>${id}</li>`).join('');
  $('intervals').innerHTML = (branchView.components || []).map((component) => {
    const interval = component.allowedInterval;
    return `<div class="card"><strong>${component.id}</strong>
      <div>[${formatYear(interval.lower)}, ${formatYear(interval.upper)}]</div>
      <div class="muted">${component.contextIds.join(', ')}</div></div>`;
  }).join('');
  $('relations').innerHTML = (branchView.relations || []).map((relation) =>
    `<div class="card">
      <span class="badge ${relation.kind}">${relation.kind}</span>
      <span class="badge ${relation.relation}">${relation.relation}</span>
      ${relation.fromComponentId} → ${relation.toComponentId}
      <div class="muted">直接证据：${(relation.directEvidenceIds || []).join(', ') || '—'}</div>
      <div class="muted">支撑路径：${(relation.pathEvidenceIds || []).join(' → ')}</div>
    </div>`).join('');
  $('evidenceTab').textContent = JSON.stringify({
    contexts: branchView.contexts,
    datingEvidence: branchView.datingEvidence,
    relationEvidence: branchView.relationEvidence,
    datingEvidenceAudit: branchView.datingEvidenceAudit,
    relationEvidenceAudit: branchView.relationEvidenceAudit,
    retractedDatingIds: branchView.retractedDatingIds,
    retractedRelationIds: branchView.retractedRelationIds,
    latestJob: branchView.latestJob
  }, null, 2);
  $('exportTab').textContent = '';
}

function formatYear(value) {
  if (value === null || value === undefined) return '∞';
  const number = Number(value);
  if (number <= 0) return `${1 - number} BCE (astronomical ${number})`;
  return `${number} CE`;
}

$('refreshBtn').addEventListener('click', refresh);

window.showSnapshot = async (snapshotId) => {
  const snapshot = await api('/api/snapshots/' + encodeURIComponent(snapshotId));
  $('exportTab').textContent = JSON.stringify(snapshot, null, 2);
  document.querySelector('[data-tab="exportTab"]').click();
};
$('branchSelect').addEventListener('change', async (event) => {
  selectedBranch = event.target.value;
  await refresh();
});

$('contextForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{ type: 'create-context', ...data, branchId: selectedBranch }]);
  event.target.reset();
});

$('datingForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{
    type: 'add-dating',
    id: data.id,
    evidenceKey: data.evidenceKey || undefined,
    branchId: selectedBranch,
    contextId: data.contextId,
    lower: signedYear(data.lowerEra, data.lowerYear),
    upper: signedYear(data.upperEra, data.upperYear),
    lowerOpen: data.lowerOpen === 'on',
    upperOpen: data.upperOpen === 'on',
    source: data.source,
    pages: data.pages,
    strength: data.strength,
    note: data.note
  }]);
  event.target.reset();
});

$('relationForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{ type: 'add-relation', ...data, branchId: selectedBranch }]);
  event.target.reset();
});

$('retractForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{
    type: data.kind === 'dating' ? 'retract-dating' : 'retract-relation',
    branchId: selectedBranch,
    evidenceId: data.evidenceId,
    reason: data.reason
  }]);
  event.target.reset();
});

$('branchForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{
    type: 'create-branch',
    ...data,
    parentBranchId: selectedBranch,
    sourceSnapshotId: data.sourceSnapshotId || undefined
  }]);
  selectedBranch = data.id;
  event.target.reset();
});

$('snapshotForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  await submitCommands([{ type: 'create-snapshot', ...data, branchId: selectedBranch }]);
  event.target.reset();
});

$('mergeForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = formObject(event.target);
  if (data.action === 'preview') {
    const preview = await api('/api/merge-preview', {
      method: 'POST',
      body: JSON.stringify(data)
    });
    $('mergeResult').innerHTML = `${JSON.stringify(preview, null, 2)}
      <div>${preview.conflicts.map((conflict, index) => `
        <div class="card">
          <strong>${conflict.evidenceKey}</strong> (${conflict.kind})
          <label>选择
            <select data-conflict-index="${index}">
              <option value="source">采用来源分支</option>
              <option value="target">保留目标分支</option>
            </select>
          </label>
        </div>`).join('')}
      </div>`;
    $('mergeResult').dataset.conflicts = JSON.stringify(preview.conflicts);
  } else {
    const conflicts = JSON.parse($('mergeResult').dataset.conflicts || '[]');
    if (!conflicts.length) {
      const preview = await api('/api/merge-preview', {
        method: 'POST',
        body: JSON.stringify(data)
      });
      conflicts.push(...preview.conflicts);
    }
    const resolutions = conflicts.map((conflict, index) => {
      const selector = document.querySelector(`[data-conflict-index="${index}"]`);
      return {
        evidenceKey: conflict.evidenceKey,
        choice: selector ? selector.value : 'source',
        reason: data.reason || `人工选择 ${index + 1}：需在审计中补充具体理由`
      };
    });
    await submitCommands([{ type: 'merge-branch', ...data, resolutions }]);
  }
});

$('exportBtn').addEventListener('click', async () => {
  const exportPayload = await api('/api/export?branch=' + encodeURIComponent(selectedBranch));
  $('exportTab').textContent = JSON.stringify(exportPayload, null, 2);
  document.querySelector('[data-tab="exportTab"]').click();
});

$('compareBtn').addEventListener('click', async () => {
  const branchIds = state.branches.filter((branch) => branch.status !== 'merged').map((branch) => branch.id);
  $('compareTab').textContent = JSON.stringify(await api('/api/compare', {
    method: 'POST',
    body: JSON.stringify({ branchIds })
  }), null, 2);
  document.querySelector('[data-tab="compareTab"]').click();
});

document.querySelectorAll('.tabs button').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((item) => item.classList.remove('active'));
    document.querySelectorAll('.tabContent').forEach((item) => item.classList.add('hidden'));
    button.classList.add('active');
    $(button.dataset.tab).classList.remove('hidden');
  });
});

refresh().catch((error) => {
  $('status').innerHTML = `<div class="status-bad">${error.message}</div>`;
});
