let state;
let current = 'main';

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: {'Content-Type': 'application/json'},
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const data = await response.json();
  if (!response.ok) {
    console.error(data);
    alert(JSON.stringify(data, null, 2));
    throw data;
  }
  return data;
}

function pretty(value) { return JSON.stringify(value, null, 2); }
function formObject(form) {
  const result = {};
  new FormData(form).forEach((value, key) => { result[key] = value; });
  form.querySelectorAll('input[type=checkbox]').forEach(input => { result[input.name] = input.checked; });
  return result;
}
async function refresh() {
  state = await api('/api/state');
  renderBranches();
  const branch = await api(`/api/branches/${encodeURIComponent(current)}`);
  document.querySelector('#conclusion').textContent = pretty(branch.inference);
  document.querySelector('#inputs').textContent = pretty({state: branch.state, info: branch.info});
  document.querySelector('#audit').textContent = pretty(await api(`/api/branches/${encodeURIComponent(current)}/audit`));
}
function renderBranches() {
  const box = document.querySelector('#branches');
  box.innerHTML = '';
  state.branches.forEach(branch => {
    const div = document.createElement('div');
    div.className = 'branch' + (branch.info.id === current ? ' active' : '');
    div.innerHTML = `<strong>${branch.info.name}</strong><br><small>${branch.info.id} · ${branch.info.status} · ${branch.info.confirmedSnapshotId || '未冻结'}</small>`;
    div.onclick = () => { current = branch.info.id; refresh(); };
    box.appendChild(div);
  });
}

document.querySelector('#refresh').onclick = refresh;
document.querySelector('#branchForm').onsubmit = async event => {
  event.preventDefault();
  await api('/api/branches', {method:'POST', body:{name: new FormData(event.target).get('name') || '工作分支'}});
  event.target.reset();
  refresh();
};
document.querySelectorAll('.tabs button').forEach(button => button.onclick = () => {
  document.querySelectorAll('.tabs button').forEach(item => item.classList.remove('active'));
  document.querySelectorAll('.tab').forEach(item => item.classList.add('hidden'));
  button.classList.add('active');
  document.querySelector('#' + button.dataset.tab + 'Form').classList.remove('hidden');
});
async function submitBatch(operations) {
  await api(`/api/branches/${encodeURIComponent(current)}/batch`, {
    method:'POST',
    body: { idempotencyKey: crypto.randomUUID(), operations }
  });
  refresh();
}
document.querySelector('#contextForm').onsubmit = event => {
  event.preventDefault();
  const value = formObject(event.target);
  submitBatch([{op:'addContext', context:{id:value.id, label:value.label, description:value.description}}]);
};
document.querySelector('#evidenceForm').onsubmit = event => {
  event.preventDefault();
  const value = formObject(event.target);
  value.strength = Number(value.strength);
  submitBatch([{op:'addEvidence', evidence:value}]);
};
document.querySelector('#datingForm').onsubmit = event => {
  event.preventDefault();
  const value = formObject(event.target);
  submitBatch([{op:'addDating', dating:{
    context:value.context,
    interval:{lower:Number(value.lower), upper:Number(value.upper), lowerEra:value.lowerEra, upperEra:value.upperEra, lowerOpen:value.lowerOpen, upperOpen:value.upperOpen},
    sourceReference:value.sourceReference, page:value.page
  }}]);
};
document.querySelector('#publishBtn').onclick = async () => {
  const label = prompt('快照标签', 'confirmed-publication');
  await api(`/api/branches/${encodeURIComponent(current)}/publish`, {method:'POST', body:{label}});
  refresh();
};
document.querySelector('#mergeBtn').onclick = async () => {
  const resolutionsText = prompt('如有冲突，粘贴 resolutions JSON；无冲突可留空', '{}');
  await api(`/api/branches/${encodeURIComponent(current)}/merge`, {method:'POST', body:{resolutions: JSON.parse(resolutionsText || '{}')}});
  current = 'main';
  refresh();
};
document.querySelector('#jobBtn').onclick = async () => {
  const started = await api(`/api/branches/${encodeURIComponent(current)}/jobs`, {method:'POST', body:{idempotencyKey:crypto.randomUUID()}});
  setTimeout(async () => alert(pretty(await api(`/api/jobs/${started.jobId}`))), 300);
};
document.querySelector('#compareBtn').onclick = async () => {
  document.querySelector('#conclusion').textContent = pretty(await api('/api/compare'));
};
document.querySelector('#exportBtn').onclick = async () => {
  const exported = await api(`/api/branches/${encodeURIComponent(current)}/export`);
  document.querySelector('#conclusion').textContent = pretty(exported);
};
refresh();
