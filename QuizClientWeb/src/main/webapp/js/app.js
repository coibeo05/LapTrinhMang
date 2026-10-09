(() => {
'use strict';
// TODO: lấy sessionId / accountId từ Module Kết nối & Tài khoản và Module Phòng chơi
const CFG = { sessionId: 'demo-session', accountId: 0 };

const LABEL = { BUTTONS: 'Chọn 1 đáp án', CHECKBOXES: 'Chọn nhiều đáp án', REORDER: 'Sắp xếp',
                TYPE_ANSWER: 'Nhập câu trả lời', RANGE: 'Đoán giá trị' };
const GUIDE = {
  BUTTONS: 'Bấm vào một phương án, sau đó nhấn "Gửi".',
  CHECKBOXES: 'Tích chọn tất cả phương án đúng (không thiếu, không thừa), sau đó nhấn "Gửi".',
  REORDER: 'Kéo thả các ô về đúng thứ tự, sau đó nhấn "Gửi".',
  TYPE_ANSWER: 'Gõ câu trả lời của bạn, sau đó nhấn "Gửi".',
  RANGE: 'Kéo thanh trượt tới giá trị bạn đoán, sau đó nhấn "Gửi".'
};

const $ = id => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };

let ws, q = null, tickT = null, introT = null, resT = null, rankT = null, toastT = null;
let answered = false, touched = false, chosen = null, endAt = 0, pendingRank = [];
let currentUser = null;

// ---------- Kết nối ----------
function connect() {
  const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + location.pathname.replace(/[^/]*$/, '') + 'ws';
  ws = new WebSocket(url);
  ws.onopen = () => { $('conn').className = 'dot on'; };
  ws.onclose = () => { $('conn').className = 'dot'; toast('Mất kết nối tới Server'); };
  ws.onmessage = e => handle(JSON.parse(e.data));
}
function send(type, payload) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type, sessionId: CFG.sessionId, payload: payload || {} }));
  else toast('Chưa kết nối tới Server');
}

// ---------- Tiện ích UI ----------
function show(id) {
  if (id !== 'question') $('qMedia').querySelectorAll('audio').forEach(a => a.pause());
  document.querySelectorAll('.screen').forEach(s => s.classList.toggle('hidden', s.id !== id));
  $('btnLeave').classList.toggle('hidden', !['intro', 'question', 'ranking'].includes(id));
  document.querySelector('main').classList.toggle('wide', id === 'createSet');
}
function toast(msg) {
  const t = $('toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.add('hidden'), 3000);
}
function clearTimers() { clearInterval(tickT); clearTimeout(introT); clearTimeout(resT); clearTimeout(rankT); }
function fillTable(tbl, rows) {
  tbl.innerHTML = '';
  const h = el('tr'); ['Hạng', 'Người chơi', 'Điểm'].forEach(x => h.appendChild(el('th', '', x))); tbl.appendChild(h);
  rows.forEach(r => {
    const tr = el('tr', r.accountId === CFG.accountId ? 'me' : '');
    tr.append(el('td', '', r.thuHang), el('td', '', r.ten || ('Người chơi ' + r.accountId)), el('td', '', r.diemHienTai));
    tbl.appendChild(tr);
  });
}

// ---------- Xử lý message từ Server ----------
function handle(m) {
  const p = m.payload || {};
  switch (m.type) {
    case 'QUESTION_DATA': onQuestion(p); break;
    case 'ANSWER_RESULT': onResult(p); break;
    case 'RANKING_UPDATE': pendingRank = p.danhSachXepHang || []; break;
    case 'PLAYER_LEFT_NOTICE': toast('Một người chơi vừa thoát. Còn ' + p.soNguoiConLai + ' người.'); break;
    case 'MATCH_CANCELLED': clearTimers(); endScreen('Trận đấu đã bị hủy', p.lyDo || '', []); break;
    case 'MATCH_END': clearTimers(); endScreen('Hết trận', 'Kết quả chung cuộc', p.ketQuaCuoi || []); break;
    case 'SET_LIST': renderSets(p.sets || []); break;
    case 'SET_CREATED': toast('Đã lưu bộ câu hỏi "' + p.ten + '"'); goHome(); break;
    case 'LOGIN_OK':
    case 'LOGIN_SUCCESS':
      currentUser = p;
      CFG.accountId = p.accountId;
      if (p.sessionId) CFG.sessionId = p.sessionId;
      $('userInfo').textContent = (p.username || p.ten || '') + ' · ' + (p.tongDiem || 0) + ' điểm';
      $('userAvatarContainer').classList.remove('hidden');
      const initChar = (p.username || p.ten || 'U').charAt(0).toUpperCase();
      $('userAvatar').textContent = initChar;
      $('modalAvatar').textContent = initChar;
      $('modalUsername').textContent = p.username || p.ten || '';
      $('modalAccountMeta').textContent = 'ID: ' + p.accountId + (p.isGoogle ? ' · Google' : '');
      $('infoScore').textContent = p.tongDiem || 0;
      $('infoWins').textContent = p.tongTranThang || 0;
      $('infoJoined').textContent = p.ngayTao || 'Hôm nay';
      toast('Đăng nhập thành công: ' + (p.username || p.ten || ''));
      goHome();
      break;
    case 'LOGIN_FAIL':
      toast(p.thongBao || p.lyDo || 'Đăng nhập thất bại');
      break;
    case 'REGISTER_SUCCESS':
      toast(p.thongBao || 'Đăng ký thành công! Hãy đăng nhập.');
      show('login');
      $('userName').value = p.username || $('regUserName').value;
      $('userPass').value = '';
      break;
    case 'REGISTER_FAIL':
      toast(p.thongBao || p.lyDo || 'Đăng ký thất bại');
      break;
    case 'HISTORY_DATA':
      renderHistory(p);
      break;
    case 'CHANGE_PASSWORD_SUCCESS':
      toast(p.thongBao || 'Đổi mật khẩu thành công');
      $('oldPass').value = ''; $('newPass').value = ''; $('newPassConfirm').value = '';
      break;
    case 'CHANGE_PASSWORD_FAIL':
      toast(p.thongBao || p.lyDo || 'Đổi mật khẩu thất bại');
      break;
    case 'LOGOUT_OK':
      doLogout();
      break;
    case 'ROOM_LIST': renderRooms(p.rooms || []); break;
    case 'ROOM_UPDATE': onRoomCreated(p); break;
    case 'ROOM_CLOSED': toast(p.lyDo || 'Phòng đã đóng'); goHome(); break;
    case 'CHAT': { const l = el('div', '', p.ten + ': ' + p.noiDung); $('chatLog').append(l); $('chatLog').scrollTop = 1e9; break; }
    case 'ERROR': toast(p.thongBao || 'Có lỗi xảy ra'); break;
  }
}
function endScreen(title, text, rows) {
  $('endTitle').textContent = title; $('endText').textContent = text;
  fillTable($('finalTable'), rows); show('end');
}

// ---------- Câu hỏi ----------
function onQuestion(p) {
  clearTimers();
  q = p; answered = false; touched = false; chosen = null;
  if (p.showIntro) {
    $('introTitle').textContent = LABEL[p.loaiCauHoi];
    $('introText').textContent = GUIDE[p.loaiCauHoi];
    let n = 3; $('introCount').textContent = n; show('intro');
    const step = () => { n--; if (n <= 0) startQuestion(); else { $('introCount').textContent = n; introT = setTimeout(step, 1000); } };
    introT = setTimeout(step, 1000);
  } else startQuestion();
}
function startQuestion() {
  render(); show('question');
  endAt = Date.now() + q.thoiGianCauHoi * 1000;
  tickT = setInterval(onTick, 100); onTick();
}
function onTick() {
  const left = Math.max(0, endAt - Date.now());
  $('timeLeft').textContent = Math.ceil(left / 1000) + 's';
  $('barFill').style.width = (left / (q.thoiGianCauHoi * 1000) * 100) + '%';
  if (left <= 0) { clearInterval(tickT); submit(true); }
}

function render() {
  const body = $('qBody'), d = q.duLieuDapAn || {};
  body.innerHTML = ''; body.classList.remove('locked');
  const mb = $('qMedia'); mb.innerHTML = '';
  if (q.anh) { const i = document.createElement('img'); i.src = q.anh; i.alt = ''; mb.appendChild(i); }
  if (q.amThanh) { const a = document.createElement('audio'); a.src = q.amThanh; a.controls = true; mb.appendChild(a); a.play().catch(() => {}); }
  $('qMeta').textContent = LABEL[q.loaiCauHoi]; $('qText').textContent = q.noiDung;
  $('qFeedback').textContent = ''; $('qFeedback').className = ''; $('btnSubmit').disabled = false;

  if (q.loaiCauHoi === 'BUTTONS') {
    d.luaChon.forEach(o => {
      const b = el('button', 'opt', o.text); b.dataset.id = o.id;
      b.onclick = () => { chosen = o.id; touched = true; body.querySelectorAll('.opt').forEach(x => x.classList.toggle('sel', x === b)); };
      body.appendChild(b);
    });
  } else if (q.loaiCauHoi === 'CHECKBOXES') {
    d.luaChon.forEach(o => {
      const l = el('label', 'opt'), c = document.createElement('input'); c.type = 'checkbox'; c.value = o.id;
      c.onchange = () => { touched = true; l.classList.toggle('sel', c.checked); };
      l.append(c, ' ' + o.text); body.appendChild(l);
    });
  } else if (q.loaiCauHoi === 'REORDER') {
    const ul = el('ul', 'reorder'); let drag = null;
    d.luaChon.forEach(o => {
      const li = el('li', 'opt', o.text); li.dataset.id = o.id; li.draggable = true;
      li.ondragstart = () => { drag = li; li.classList.add('drag'); };
      li.ondragend = () => { li.classList.remove('drag'); touched = true; };
      li.ondragover = e => {
        e.preventDefault(); if (!drag || drag === li) return;
        const r = li.getBoundingClientRect();
        ul.insertBefore(drag, e.clientY < r.top + r.height / 2 ? li : li.nextSibling);
      };
      ul.appendChild(li);
    });
    body.appendChild(ul);
  } else if (q.loaiCauHoi === 'TYPE_ANSWER') {
    const i = document.createElement('input'); i.type = 'text'; i.placeholder = 'Nhập câu trả lời...';
    i.oninput = () => { touched = i.value.trim() !== ''; };
    body.appendChild(i);
  } else if (q.loaiCauHoi === 'RANGE') {
    const r = document.createElement('input'); r.type = 'range'; r.min = d.min; r.max = d.max; r.step = d.step;
    r.value = Math.round((d.min + d.max) / 2);
    const v = el('div', 'rangeval', r.value);
    r.oninput = () => { v.textContent = r.value; touched = true; };
    body.append(v, r);
  }
}

function emptyAnswer() {
  return (q.loaiCauHoi === 'CHECKBOXES' || q.loaiCauHoi === 'REORDER') ? [] : (q.loaiCauHoi === 'TYPE_ANSWER' ? '' : null);
}
function collect() {
  const body = $('qBody');
  switch (q.loaiCauHoi) {
    case 'BUTTONS': return chosen;
    case 'CHECKBOXES': return [...body.querySelectorAll('input:checked')].map(i => i.value);
    case 'REORDER': return [...body.querySelectorAll('li')].map(li => li.dataset.id);
    case 'TYPE_ANSWER': return body.querySelector('input').value;
    case 'RANGE': return Number(body.querySelector('input').value);
  }
}
function lock() {
  $('btnSubmit').disabled = true; $('qBody').classList.add('locked');
  $('qBody').querySelectorAll('input').forEach(i => { i.disabled = true; });
  $('qBody').querySelectorAll('li').forEach(li => { li.draggable = false; });
}
function submit(auto) {
  if (answered || !q) return;
  answered = true; clearInterval(tickT); lock();
  // Hết giờ mà chưa thao tác -> gửi đáp án rỗng. Client KHÔNG gửi kèm thời gian.
  send('SUBMIT_ANSWER', { questionId: q.questionId, dapAnChon: (auto && !touched) ? emptyAnswer() : collect() });
  $('qFeedback').textContent = 'Đã gửi đáp án. Đang chờ kết quả...';
}

// ---------- Kết quả & xếp hạng ----------
function onResult(p) {
  if (!q || p.questionId !== q.questionId) return;
  answered = true; clearInterval(tickT); lock();
  const me = (p.danhSachKetQua || []).find(r => r.accountId === CFG.accountId) || { dungSai: false, diemVuaCong: 0 };
  $('qFeedback').textContent = (me.dungSai ? 'Chính xác!' : 'Chưa đúng.') + '  +' + me.diemVuaCong + ' điểm';
  $('qFeedback').className = me.dungSai ? 'ok' : 'bad';
  paint(p.dapAnDung);
  resT = setTimeout(showRanking, 3000);   // hiển thị đáp án đúng 3 giây
}
function paint(c) {
  const t = q.loaiCauHoi, body = $('qBody');
  if (t === 'BUTTONS') {
    body.querySelectorAll('.opt').forEach(b => {
      if (b.dataset.id === c) b.classList.add('right'); else if (b.classList.contains('sel')) b.classList.add('wrong');
    });
  } else if (t === 'CHECKBOXES') {
    body.querySelectorAll('label').forEach(l => {
      const i = l.querySelector('input');
      if (c.includes(i.value)) l.classList.add('right'); else if (i.checked) l.classList.add('wrong');
    });
  } else {
    let s;
    if (t === 'REORDER') s = c.map(id => q.duLieuDapAn.luaChon.find(o => o.id === id).text).join(' → ');
    else if (t === 'TYPE_ANSWER') s = c.join(' / ');
    else s = String(c);
    body.appendChild(el('div', 'answerbox', 'Đáp án đúng: ' + s));
  }
}
function showRanking() {
  fillTable($('rankTable'), pendingRank);
  const me = pendingRank.find(r => r.accountId === CFG.accountId);
  if (me) $('score').textContent = 'Điểm: ' + me.diemHienTai;
  show('ranking');
  rankT = setTimeout(() => send('REQUEST_QUESTION'), 3000);   // xem xong xếp hạng thì xin câu tiếp
}

// ---------- Sảnh: bộ câu hỏi, tạo kho, tạo phòng ----------
let sets = [];

function goHome() { $('score').textContent = ''; $('chatLog').innerHTML = ''; show('home'); send('LIST_SETS'); send('LIST_ROOMS'); }
function renderRooms(list) {
  const box = $('roomList'); box.innerHTML = '';
  if (!list.length) { box.appendChild(el('p', 'hint', 'Chưa có phòng nào đang mở.')); return; }
  list.forEach(r => {
    const c = el('div', 'card'), b = el('button', '', 'Vào');
    b.onclick = () => send('JOIN_ROOM', { roomId: r.roomId });
    c.append(el('b', '', r.ten + ' (mã ' + r.roomId + ')'), el('span', '', r.tenBo + ' · ' + r.soNguoi + '/' + r.toiDa), b);
    box.appendChild(c);
  });
}

function renderSets(list) {
  sets = list;
  const box = $('setList'); box.innerHTML = '';
  if (!list.length) { box.appendChild(el('p', 'hint', 'Chưa có bộ câu hỏi nào.')); return; }
  list.forEach(x => {
    const c = el('div', 'card'), l = el('div');
    l.appendChild(el('b', '', x.ten));
    l.appendChild(el('span', 'badge' + (x.laCuaToi ? ' mine' : ''), x.laCuaToi ? 'Của tôi' : 'Hệ thống'));
    c.append(l, el('span', '', x.soCau + ' câu'));
    box.appendChild(c);
  });
}

// ---------- Trình soạn bộ câu hỏi ----------
const ICON = { BUTTONS: '◉', CHECKBOXES: '☑', REORDER: '⇅', TYPE_ANSWER: '⌨', RANGE: '↔' };
let slides = [], cur = 0, dragS = null;
const blank = t => ({ type: t, noiDung: '', thoiGian: '15', anh: '', amThanh: '', ...({ BUTTONS: { dung: '', sai: ['', '', ''] }, CHECKBOXES: { dung: [''], sai: ['', ''] },
  REORDER: { thuTu: ['', '', ''] }, TYPE_ANSWER: { chapNhan: [''] }, RANGE: { min: '0', max: '100', step: '1', dapAn: '', saiSo: '0' } })[t] });
const T = v => String(v).trim(), F = a => a.map(T).filter(Boolean), N = v => T(v) === '' ? NaN : Number(v);
const dup = a => new Set(a.map(x => x.toLowerCase().replace(/\s+/g, ' '))).size !== a.length;

function problem(s) {   // null = hợp lệ (Server kiểm tra lại các luật này)
  if (!T(s.noiDung)) return 'Chưa nhập nội dung câu hỏi';
  const tg = N(s.thoiGian);
  if (!Number.isInteger(tg) || tg < 5 || tg > 90) return 'Thời gian phải là số nguyên từ 5 đến 90 giây';
  const d = s.type === 'BUTTONS' ? (T(s.dung) ? [T(s.dung)] : []) : F(s.dung || []), w = F(s.sai || []);
  switch (s.type) {
    case 'BUTTONS': case 'CHECKBOXES':
      return !d.length ? 'Chưa nhập đáp án đúng' : !w.length ? 'Cần ít nhất 1 đáp án sai' : dup([...d, ...w]) ? 'Các đáp án không được trùng nhau' : null;
    case 'REORDER': { const a = F(s.thuTu); return a.length < 2 ? 'Cần ít nhất 2 ô' : dup(a) ? 'Các ô không được trùng nhau' : null; }
    case 'TYPE_ANSWER': return F(s.chapNhan).length ? null : 'Cần ít nhất 1 đáp án được chấp nhận';
    default: {
      const [mn, mx, st, v] = [s.min, s.max, s.step, s.dapAn].map(N), tol = T(s.saiSo) === '' ? 0 : N(s.saiSo);
      if ([mn, mx, st, v, tol].some(Number.isNaN)) return 'Hãy nhập đủ nhỏ nhất, lớn nhất, bước, đáp án';
      if (mn >= mx || st <= 0 || tol < 0) return 'Cần nhỏ nhất < lớn nhất, bước > 0, sai số ≥ 0';
      if (v < mn || v > mx) return 'Đáp án phải nằm trong khoảng nhỏ nhất – lớn nhất';
      const k = (v - mn) / st;
      return Math.abs(k - Math.round(k)) > 1e-9 && tol < st / 2 ? 'Đáp án không nằm trên bước nhảy: chỉnh bước hoặc tăng sai số' : null;
    }
  }
}
const payload = s => ({ loaiCauHoi: s.type, noiDung: T(s.noiDung), thoiGian: N(s.thoiGian), anh: s.anh, amThanh: s.amThanh, ...({
  BUTTONS: () => ({ dung: [T(s.dung)], sai: F(s.sai) }), CHECKBOXES: () => ({ dung: F(s.dung), sai: F(s.sai) }),
  REORDER: () => ({ thuTu: F(s.thuTu) }), TYPE_ANSWER: () => ({ chapNhan: F(s.chapNhan) }),
  RANGE: () => ({ min: N(s.min), max: N(s.max), step: N(s.step), dapAn: N(s.dapAn), saiSo: T(s.saiSo) === '' ? 0 : N(s.saiSo) }) })[s.type]() });

function inp(v, ph, cls, on, area) {
  const i = document.createElement(area ? 'textarea' : 'input');
  i.className = 'qe-in ' + cls; i.value = v; i.placeholder = ph; i.maxLength = area ? 200 : 100;
  i.oninput = () => { on(i.value); strip(); }; return i;
}
function field(cls, txt, ...kids) { const f = el('div', 'qe-field'); f.append(el('span', 'qe-tag ' + cls, txt), ...kids); return f; }
function list(arr, o) {   // o: cls, ph, min, max, add, sort, fixed
  const box = el('div', 'qe-list'), redo = () => { form(); strip(); };
  arr.forEach((v, i) => {
    const r = el('div', 'qe-row');
    if (o.sort) [['↑', -1], ['↓', 1]].forEach(([c, d]) => {
      const b = el('button', 'qe-mv', c); b.disabled = i + d < 0 || i + d >= arr.length;
      b.onclick = () => { [arr[i], arr[i + d]] = [arr[i + d], arr[i]]; redo(); }; r.appendChild(b);
    });
    r.appendChild(inp(v, o.ph(i), o.cls, x => { arr[i] = x; }));
    if (!o.fixed && arr.length > o.min) { const x = el('button', 'qe-x', '×'); x.onclick = () => { arr.splice(i, 1); redo(); }; r.appendChild(x); }
    box.appendChild(r);
  });
  if (!o.fixed && arr.length < o.max) { const a = el('button', 'qe-add', o.add); a.onclick = () => { arr.push(''); redo(); }; box.appendChild(a); }
  return box;
}
function form() {
  const m = $('qeMain'), s = slides[cur]; m.innerHTML = '';
  if (!s) { m.appendChild(el('div', 'qe-empty', 'Chưa có câu hỏi nào. Bấm nút + để thêm câu.')); return; }
  const f = el('div', 'qe-form'), ph = i => i ? 'Không bắt buộc' : 'Bắt buộc'; m.appendChild(f);
  f.append(el('div', 'qe-title', 'Câu ' + (cur + 1) + '/' + slides.length + ' · ' + LABEL[s.type]),
    field('q', 'Câu hỏi', inp(s.noiDung, 'Bắt buộc', 'q', x => { s.noiDung = x; }, true)),
    field('neu', 'Ảnh minh họa (không bắt buộc)', media(s, 'anh')), field('neu', 'Âm thanh (không bắt buộc)', media(s, 'amThanh')));
  if (s.type === 'BUTTONS') f.append(field('good', 'Đáp án đúng', inp(s.dung, 'Bắt buộc', 'good', x => { s.dung = x; })),
    field('bad', 'Đáp án sai', list(s.sai, { cls: 'bad', fixed: true, ph })));
  else if (s.type === 'CHECKBOXES') f.append(
    field('good', 'Các đáp án đúng', list(s.dung, { cls: 'good', min: 1, max: 6 - s.sai.length, add: '+ Thêm đáp án đúng', ph })),
    field('bad', 'Các đáp án sai', list(s.sai, { cls: 'bad', min: 1, max: 6 - s.dung.length, add: '+ Thêm đáp án sai', ph })));
  else if (s.type === 'REORDER') f.appendChild(field('neu', 'Các ô theo ĐÚNG thứ tự (người chơi sẽ thấy bị xáo trộn)',
    list(s.thuTu, { cls: 'good', min: 2, max: 6, add: '+ Thêm ô', sort: true, ph: i => 'Ô ' + (i + 1) })));
  else if (s.type === 'TYPE_ANSWER') f.appendChild(field('good', 'Đáp án được chấp nhận (không phân biệt hoa/thường)',
    list(s.chapNhan, { cls: 'good', min: 1, max: 5, add: '+ Thêm cách viết khác', ph })));
  else {
    const g = el('div', 'qe-grid');
    [['min', 'Nhỏ nhất'], ['max', 'Lớn nhất'], ['step', 'Bước nhảy'], ['dapAn', 'Đáp án đúng'], ['saiSo', 'Sai số cho phép']].forEach(([k, n]) => {
      const l = el('label', '', n), i = inp(s[k], '', 'good', x => { s[k] = x; }); i.type = 'number'; i.step = 'any'; l.appendChild(i); g.appendChild(l);
    });
    f.appendChild(field('good', 'Thang đo và đáp án', g));
  }
  const t = inp(s.thoiGian, '15', 'neu', x => { s.thoiGian = x; }); t.type = 'number'; t.min = 5; t.max = 90; t.step = 1;
  f.appendChild(field('neu', 'Thời gian trả lời (giây, từ 5 đến 90)', t));
}
function media(s, kind) {   // kind: 'anh' | 'amThanh'
  const img = kind === 'anh', box = el('div', 'qe-media');
  if (s[kind]) {
    const p = document.createElement(img ? 'img' : 'audio'); p.src = s[kind]; if (img) p.className = 'qe-prev'; else p.controls = true;
    const x = el('button', 'qe-x', '× Xóa'); x.onclick = () => { s[kind] = ''; form(); };
    box.append(p, x);
  } else {
    const f = document.createElement('input'); f.type = 'file';
    f.accept = img ? 'image/png,image/jpeg,image/webp' : 'audio/mpeg,audio/ogg,audio/wav,audio/mp4';
    f.onchange = () => upload(f.files[0], s, kind, box);
    box.appendChild(f);
  }
  return box;
}
async function upload(file, s, kind, box) {
  if (!file) return;
  if (file.size > (kind === 'anh' ? 2 : 5) * 1048576) return toast('File quá lớn (ảnh tối đa 2 MB, âm thanh tối đa 5 MB)');
  box.textContent = 'Đang tải lên...';
  try {
    const fd = new FormData(); fd.append('file', file);
    const r = await fetch('upload?kind=' + kind, { method: 'POST', body: fd }), j = await r.json();
    if (!r.ok) throw new Error(j.loi);
    s[kind] = j.url;
  } catch (e) { toast(e.message || 'Tải lên thất bại'); }
  form();
}
function strip(go) {   // dải slide câu hỏi, kéo thả để đổi thứ tự
  const box = $('slides'), x = box.scrollLeft; box.innerHTML = '';
  slides.forEach((s, i) => {
    const t = el('div', 'thumb' + (i === cur ? ' cur' : '') + (problem(s) ? ' bad' : '')), d = el('button', 'tx', '×');
    t.draggable = true;
    t.onclick = () => { cur = i; strip(); form(); };
    d.onclick = e => { e.stopPropagation(); slides.splice(i, 1); if (i < cur) cur--; cur = Math.max(0, Math.min(cur, slides.length - 1)); strip(); form(); };
    t.ondragstart = e => { dragS = i; e.dataTransfer.setData('text/plain', i); };
    t.ondragover = e => e.preventDefault();
    t.ondrop = e => {
      e.preventDefault(); if (dragS === null || dragS === i) return;
      const c = slides[cur], [m] = slides.splice(dragS, 1); slides.splice(i, 0, m); cur = slides.indexOf(c); dragS = null; strip(); form();
    };
    t.append(el('span', 'tn', 'Câu ' + (i + 1)), el('span', 'tt', LABEL[s.type]), el('span', 'tq', T(s.noiDung) || 'Chưa có nội dung'), d);
    box.appendChild(t);
    if (go && i === cur) t.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  });
  if (!go) box.scrollLeft = x;
}

function syncCount() {
  const s = sets.find(x => String(x.setId) === $('roomSet').value);
  if (!s) return;
  $('roomCount').max = s.soCau;
  $('roomCount').value = Math.min(Number($('roomCount').value) || s.soCau, s.soCau);
}

function onRoomCreated(p) {
  $('roomTitle').textContent = p.ten + ' (mã ' + p.roomId + ')';
  $('roomInfo').textContent = 'Mã phòng: ' + p.roomId + ' · Bộ câu hỏi: ' + p.tenBo + ' · ' + p.soCau + ' câu';
  const ul = $('playerList'); ul.innerHTML = '';
  (p.nguoiChoi || []).forEach(x => ul.appendChild(el('li', '', x.ten + (x.accountId === p.chuPhong ? ' (chủ phòng)' : ''))));
  $('btnStart').classList.toggle('hidden', p.chuPhong !== CFG.accountId);
  $('btnAddBot').classList.toggle('hidden', p.chuPhong !== CFG.accountId);
  show('waiting');
}

const openPicker = () => slides.length >= 50 ? toast('Tối đa 50 câu mỗi bộ') : $('typePicker').classList.remove('hidden');
Object.keys(ICON).forEach(k => {
  const b = el('button', 'pick', ICON[k] + '  ' + LABEL[k]);
  b.onclick = () => { $('typePicker').classList.add('hidden'); slides.push(blank(k)); cur = slides.length - 1; strip(true); form(); };
  $('pickGrid').appendChild(b);
});
$('btnAddSlide').onclick = openPicker;
$('btnClosePicker').onclick = () => $('typePicker').classList.add('hidden');
$('btnNewSet').onclick = () => { slides = []; cur = 0; $('setName').value = ''; strip(); form(); show('createSet'); openPicker(); };
$('btnCancelSet').onclick = () => { if (!slides.length || window.confirm('Bỏ bộ câu hỏi đang soạn?')) goHome(); };
$('btnSaveSet').onclick = () => {
  const ten = $('setName').value.trim();
  if (!ten) { $('setName').focus(); return toast('Hãy nhập tên bộ câu hỏi'); }
  if (!slides.length) return toast('Hãy thêm ít nhất 1 câu');
  for (let i = 0; i < slides.length; i++) {
    const p = problem(slides[i]);
    if (p) { cur = i; strip(true); form(); return toast('Câu ' + (i + 1) + ': ' + p); }
  }
  send('CREATE_SET', { ten, cauHoi: slides.map(payload) });
};
$('btnNewRoom').onclick = () => {
  if (!sets.length) return toast('Chưa có bộ câu hỏi để chọn');
  const sel = $('roomSet'); sel.innerHTML = '';
  sets.forEach(x => { const o = el('option', '', x.ten + ' (' + x.soCau + ' câu)'); o.value = x.setId; sel.appendChild(o); });
  $('roomCount').value = sets[0].soCau; syncCount(); show('createRoom');
};
$('roomSet').onchange = () => { $('roomCount').value = 999; syncCount(); };
$('btnCancelRoom').onclick = goHome;
$('btnLeaveRoom').onclick = () => { send('LEAVE_ROOM'); goHome(); };
const joinCode = () => { const c = $('joinCode').value.trim(); if (!c) return toast('Hãy nhập mã phòng'); send('JOIN_ROOM', { roomId: c }); $('joinCode').value = ''; };
$('btnJoinCode').onclick = joinCode;
$('joinCode').onkeydown = e => { if (e.key === 'Enter') joinCode(); };
$('btnAddBot').onclick = () => send('ADD_BOT');
$('btnRefresh').onclick = () => send('LIST_ROOMS');
$('btnChat').onclick = () => { const i = $('chatIn'); if (i.value.trim()) send('CHAT', { noiDung: i.value }); i.value = ''; };
$('chatIn').onkeydown = e => { if (e.key === 'Enter') $('btnChat').click(); };
// ---------- Xác thực & Hồ sơ ----------
function doLogout() {
  CFG.accountId = 0;
  currentUser = null;
  $('userAvatarContainer').classList.add('hidden');
  $('profileModal').classList.add('hidden');
  $('userInfo').textContent = '';
  $('userName').value = '';
  $('userPass').value = '';
  show('login');
  toast('Đã đăng xuất');
}

function renderHistory(p) {
  $('infoMatches').textContent = p.totalMatches || 0;
  $('infoWinRate').textContent = (p.winRate || 0) + '%';
  const hl = $('historyList');
  hl.innerHTML = '';
  const list = p.history || [];
  if (!list.length) {
    hl.innerHTML = '<p class="hint" style="text-align:center;padding:16px">Chưa có trận đấu nào được ghi nhận.</p>';
    return;
  }
  list.forEach(h => {
    const card = el('div', 'history-card');
    const left = el('div', '');
    left.append(el('div', 'history-title', h.chuDe || 'Trận đấu Quiz'));
    left.append(el('div', 'history-time', h.thoiGian || ''));
    const right = el('div', '', '');
    right.style.textAlign = 'right';
    right.append(el('div', 'history-rank' + (h.thuHang === 1 ? ' top1' : ''), 'Hạng ' + h.thuHang));
    right.append(el('div', 'history-pts', '+' + h.diemSo + ' điểm'));
    card.append(left, right);
    hl.appendChild(card);
  });
}

const login = () => {
  const u = $('userName').value.trim();
  const pw = $('userPass').value;
  if (!u) return toast('Hãy nhập tên đăng nhập');
  send('LOGIN', { username: u, password: pw, ten: u });
};

const doRegister = () => {
  const u = $('regUserName').value.trim();
  const p1 = $('regUserPass').value;
  const p2 = $('regUserPassConfirm').value;
  if (!u || u.length < 3) return toast('Tên đăng nhập từ 3 đến 30 ký tự');
  if (!p1 || p1.length < 4) return toast('Mật khẩu tối thiểu 4 ký tự');
  if (p1 !== p2) return toast('Mật khẩu xác nhận không khớp');
  send('REGISTER', { username: u, password: p1, confirmPassword: p2 });
};

$('btnLogin').onclick = login;
$('userName').onkeydown = e => { if (e.key === 'Enter') $('userPass').focus(); };
$('userPass').onkeydown = e => { if (e.key === 'Enter') login(); };

$('btnDoRegister').onclick = doRegister;
$('regUserName').onkeydown = e => { if (e.key === 'Enter') $('regUserPass').focus(); };
$('regUserPass').onkeydown = e => { if (e.key === 'Enter') $('regUserPassConfirm').focus(); };
$('regUserPassConfirm').onkeydown = e => { if (e.key === 'Enter') doRegister(); };

$('btnSwitchRegister').onclick = () => show('register');
$('btnSwitchLogin').onclick = () => show('login');
$('btnBackToLogin').onclick = () => show('login');

// Đăng nhập Google: Google trả ID token (JWT) -> gửi cho Server tự xác minh (GoogleTokenVerifier).
// Client ID không phải bí mật, nhưng PHẢI trùng Client ID ở Server và đã khai báo origin http://localhost:8081 trên Google Cloud.
// Nút chính thức của Google được đặt trong suốt đè lên nút "Tiếp tục với Google" để giữ giao diện của nhóm.
const GOOGLE_CLIENT_ID = '360362842720-ge5oh5ptjin31t0k9vvp8jd1124ti3os.apps.googleusercontent.com';
function initGoogle() {
  if (!window.google || !google.accounts || !google.accounts.id) return false;
  const btn = $('btnGoogleLogin'), box = $('gsiBtn');
  google.accounts.id.initialize({
    client_id: GOOGLE_CLIENT_ID,
    callback: r => send('GOOGLE_LOGIN', { idToken: r.credential })
  });
  google.accounts.id.renderButton(box, { theme: 'outline', size: 'large', width: Math.min(400, Math.max(200, btn.offsetWidth || 300)) });
  if (btn.offsetHeight) box.style.transform = 'scaleY(' + (btn.offsetHeight / 40) + ')';   // phủ kín chiều cao nút
  return true;
}
$('btnGoogleLogin').onclick = () => toast('Chưa tải được Google, hãy kiểm tra kết nối mạng');   // chỉ chạy khi nút Google chưa nạp
let gTries = 0;
const gTimer = setInterval(() => { if (initGoogle() || ++gTries > 50) clearInterval(gTimer); }, 200);

// Avatar & Profile Modal
$('userAvatarContainer').onclick = () => {
  $('profileModal').classList.remove('hidden');
  send('GET_HISTORY');
};
$('btnCloseProfile').onclick = () => {
  $('profileModal').classList.add('hidden');
};

document.querySelectorAll('.ptab').forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll('.ptab').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    const tabId = btn.getAttribute('data-tab');
    document.querySelectorAll('.ptab-pane').forEach(p => p.classList.add('hidden'));
    $(tabId).classList.remove('hidden');
    if (tabId === 'tabHistory') send('GET_HISTORY');
  };
});

$('btnSavePassword').onclick = () => {
  const oldP = $('oldPass').value;
  const newP = $('newPass').value;
  const confirmP = $('newPassConfirm').value;
  if (!oldP) return toast('Hãy nhập mật khẩu hiện tại');
  if (!newP || newP.length < 4) return toast('Mật khẩu mới tối thiểu 4 ký tự');
  if (newP !== confirmP) return toast('Mật khẩu xác nhận không khớp');
  send('CHANGE_PASSWORD', { oldPassword: oldP, newPassword: newP });
};

$('btnLogout').onclick = () => {
  send('LOGOUT');
  doLogout();
};
$('btnCreateRoom').onclick = () => send('CREATE_ROOM', {
  ten: $('roomName').value.trim(), setId: Number($('roomSet').value), soCau: Number($('roomCount').value) || 1, an: $('roomHidden').checked });

// ---------- Nút bấm ----------
$('btnStart').onclick = () => { $('score').textContent = ''; send('START_MATCH'); };
$('btnSubmit').onclick = () => submit(false);
$('btnLeave').onclick = () => $('confirm').classList.remove('hidden');
$('btnNo').onclick = () => $('confirm').classList.add('hidden');
$('btnYes').onclick = () => {
  $('confirm').classList.add('hidden'); clearTimers();
  send('LEAVE_MATCH'); q = null; goHome();
};
$('btnHome').onclick = goHome;

connect(); show('login');
})();