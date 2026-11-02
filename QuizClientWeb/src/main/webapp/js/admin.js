(() => {
'use strict';
// Trang quản trị (admin.html). Nói chuyện với QuizServer qua cùng cầu nối WebSocket /ws như trang người chơi.
// Gói tin: ADMIN_LOGIN {password} -> ADMIN_OK | ADMIN_FAIL {thongBao}
//          ADMIN_OVERVIEW -> ADMIN_DATA {stats, rooms, players, logs}
//          ADMIN_CLOSE_ROOM {roomId} | ADMIN_KICK_ROOM {accountId} | ADMIN_DISCONNECT {accountId} -> ADMIN_DATA mới
// Mọi dữ liệu hiển thị đều đi qua textContent (tên người chơi / tên phòng do người dùng tự đặt, không được chèn HTML).

const POLL_MS = 2000;
const $ = id => document.getElementById(id);
const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls)
        e.className = cls;
    if (text !== undefined && text !== null)
        e.textContent = text;
    return e;
};
// bỏ dấu + chữ thường để tìm kiếm tiếng Việt dễ hơn
const norm = s => String(s == null ? '' : s).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd');

let ws = null, authed = false, pw = '', pendingPw = '';
let pollT = null, reconnT = null, reconnTries = 0, toastT = null;
let data = {stats: {}, rooms: [], players: [], logs: []};

// ---------- Kết nối ----------
function connect() {
    clearTimeout(reconnT);
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + location.pathname.replace(/[^/]*$/, '') + 'ws';
    const sock = ws = new WebSocket(url);
    sock.onopen = () => {
        if (sock !== ws)
            return;
        reconnTries = 0;
        $('conn').className = 'dot on';
        setStatus(true);
        if (authed)
            send('ADMIN_LOGIN', {password: pw});   // nối lại sau khi rớt mạng: xác thực lại tự động
    };
    sock.onclose = () => {
        if (sock !== ws)
            return;
        $('conn').className = 'dot';
        setStatus(false);
        stopPoll();
        reconnT = setTimeout(connect, Math.min(1000 * Math.pow(2, reconnTries++), 5000));
    };
    sock.onmessage = e => {
        if (sock !== ws)
            return;
        let m;
        try {
            m = JSON.parse(e.data);
        } catch (err) {
            return;
        }
        handle(m);
    };
}

function send(type, payload) {
    if (ws && ws.readyState === 1) {
        ws.send(JSON.stringify({type, payload: payload || {}}));
        return true;
    }
    return false;
}

function setStatus(ok) {
    const s = $('srvStatus');
    s.className = 'pill ' + (ok ? 'ok' : 'bad');
    s.textContent = ok ? 'Máy chủ đang hoạt động' : 'Mất kết nối tới máy chủ';
}

function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toastT);
    toastT = setTimeout(() => t.classList.add('hidden'), 3000);
}

// ---------- Đăng nhập ----------
function doLogin() {
    const v = $('pw').value;
    if (!v) {
        $('loginErr').textContent = 'Hãy nhập mật khẩu quản trị';
        return;
    }
    $('loginErr').textContent = '';
    pendingPw = v;
    if (!send('ADMIN_LOGIN', {password: v}))
        $('loginErr').textContent = 'Chưa kết nối tới Server, đang thử nối lại...';
}

function showDash() {
    $('loginBox').classList.add('hidden');
    $('dash').classList.remove('hidden');
    $('btnLogout').classList.remove('hidden');
    $('pw').value = '';
    $('loginErr').textContent = '';
}

function showLogin(err) {
    stopPoll();
    authed = false;
    pw = '';
    $('dash').classList.add('hidden');
    $('btnLogout').classList.add('hidden');
    $('loginBox').classList.remove('hidden');
    $('loginErr').textContent = err || '';
}

function startPoll() {
    stopPoll();
    request();
    pollT = setInterval(request, POLL_MS);
}

function stopPoll() {
    clearInterval(pollT);
    pollT = null;
}

function request() {
    if (authed)
        send('ADMIN_OVERVIEW');
}

// ---------- Xử lý gói tin ----------
function handle(m) {
    const p = m.payload || {};
    switch (m.type) {
        case 'ADMIN_OK':
            authed = true;
            if (pendingPw)
                pw = pendingPw;
            pendingPw = '';
            showDash();
            startPoll();
            break;
        case 'ADMIN_FAIL': {
            const msg = p.thongBao || 'Có lỗi xảy ra';
            if (authed && /mật khẩu|xác thực/i.test(msg))
                showLogin(msg);   // mất quyền (server khởi động lại, đổi mật khẩu...) -> đăng nhập lại
            else if (!authed)
                $('loginErr').textContent = msg;
            else
                toast(msg);
            break;
        }
        case 'ADMIN_DATA':
            data = {
                stats: p.stats || {}, rooms: p.rooms || [],
                players: p.players || [], logs: p.logs || []
            };
            render();
            break;
        case 'ERROR':
            if (!authed)
                $('loginErr').textContent = p.thongBao || 'Có lỗi xảy ra';
            else
                toast(p.thongBao || 'Có lỗi xảy ra');
            break;
        default:
            break;
    }
}

// ---------- Hiển thị ----------
function render() {
    renderStats();
    renderRooms();
    renderPlayers();
    renderLogs();
}

function fmtUptime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
    return d > 0 ? d + ' ngày ' + h + ' giờ' : h > 0 ? h + ' giờ ' + m + ' phút' : m + ' phút';
}

function renderStats() {
    const s = data.stats;
    const inRoom = data.players.filter(x => x.roomId).length;
    $('stRooms').textContent = s.phong || 0;
    $('stRoomsSub').textContent = (s.dangThi || 0) + ' đang thi đấu';
    $('stOnline').textContent = s.online || 0;
    $('stOnlineSub').textContent = inRoom + ' trong phòng';
    $('stCpu').textContent = (s.cpu || 0) + '%';
    $('stCpuSub').textContent = 'Bộ nhớ ' + (s.boNhoMB || 0) + ' MB · chạy ' + fmtUptime(s.uptimeGiay);
    if (s.pingTB === undefined || s.pingTB < 0) {
        $('stPing').textContent = '—';
        $('stPingSub').textContent = 'Chưa có dữ liệu';
    } else {
        $('stPing').textContent = s.pingTB + ' ms';
        $('stPingSub').textContent = 'Đo từ ' + (s.pingMau || 0) + ' người chơi';
    }
}

function emptyRow(cols, text) {
    const tr = el('tr'), td = el('td', 'empty', text);
    td.colSpan = cols;
    tr.appendChild(td);
    return tr;
}

function renderRooms() {
    const q = norm($('roomQ').value.trim());
    const tb = $('roomTable').querySelector('tbody');
    tb.textContent = '';
    $('roomCount').textContent = data.rooms.length;
    const rows = data.rooms.filter(r => !q || norm([r.roomId, r.ten, r.tenBo, r.chuPhong].join(' ')).includes(q));
    if (!rows.length) {
        tb.appendChild(emptyRow(6, data.rooms.length ? 'Không có phòng nào khớp' : 'Chưa có phòng nào đang mở'));
        return;
    }
    rows.forEach(r => {
        const tr = el('tr');
        const c1 = el('td');
        c1.append(el('div', 'cell-main', r.ten), el('div', 'cell-sub', '#' + r.roomId + (r.an ? ' · Phòng ẩn' : '')));
        const c4 = el('td');
        c4.appendChild(el('span', 'pill ' + (r.dangChoi ? 'play' : 'ok'), r.dangChoi ? 'Đang thi đấu' : 'Đang chờ'));
        const c6 = el('td', 'act');
        const b = el('button', 'sm warn', 'Đóng phòng');
        b.type = 'button';
        b.onclick = () => {
            if (window.confirm('Đóng phòng "' + r.ten + '" (#' + r.roomId + ')? Mọi người trong phòng sẽ bị đưa về sảnh.'))
                send('ADMIN_CLOSE_ROOM', {roomId: r.roomId});
        };
        c6.appendChild(b);
        tr.append(c1, el('td', '', r.chuPhong), el('td', '', r.soNguoi + '/' + r.toiDa), c4, el('td', '', r.tenBo), c6);
        tb.appendChild(tr);
    });
}

function renderPlayers() {
    const q = norm($('playerQ').value.trim());
    const tb = $('playerTable').querySelector('tbody');
    tb.textContent = '';
    $('playerCount').textContent = data.players.length;
    const rows = data.players.filter(x => !q || norm([x.username, x.roomTen, x.roomId].join(' ')).includes(q));
    if (!rows.length) {
        tb.appendChild(emptyRow(4, data.players.length ? 'Không có người chơi nào khớp' : 'Chưa có ai online'));
        return;
    }
    rows.forEach(x => {
        const tr = el('tr');
        const c1 = el('td'), who = el('div', 'who-cell');
        who.append(el('span', 'av', (x.username || '?').charAt(0).toUpperCase()), el('span', '', x.username));
        c1.appendChild(who);
        const room = x.roomId ? data.rooms.find(r => r.roomId === x.roomId) : null;
        const c2 = el('td');
        if (x.roomId) {
            c2.append(el('div', 'cell-main', x.roomTen || 'Phòng'), el('div', 'cell-sub', '#' + x.roomId));
        } else {
            c2.textContent = 'Ở sảnh';
        }
        const c3 = el('td');
        c3.appendChild(x.roomId
                ? el('span', 'pill ' + (room && room.dangChoi ? 'play' : 'ok'), room && room.dangChoi ? 'Đang thi đấu' : 'Trong phòng')
                : el('span', 'pill', 'Ở sảnh'));
        const c4 = el('td', 'act');
        if (x.roomId) {
            const kick = el('button', 'sm warn', 'Mời ra');
            kick.type = 'button';
            kick.onclick = () => {
                if (window.confirm('Mời "' + x.username + '" ra khỏi phòng?'))
                    send('ADMIN_KICK_ROOM', {accountId: x.accountId});
            };
            c4.appendChild(kick);
        }
        const dis = el('button', 'sm warn', 'Ngắt kết nối');
        dis.type = 'button';
        dis.onclick = () => {
            if (window.confirm('Ngắt kết nối của "' + x.username + '"? Người này sẽ phải đăng nhập lại.'))
                send('ADMIN_DISCONNECT', {accountId: x.accountId});
        };
        c4.appendChild(dis);
        tr.append(c1, c2, c3, c4);
        tb.appendChild(tr);
    });
}

function renderLogs() {
    const ul = $('logList');
    ul.textContent = '';
    if (!data.logs.length) {
        ul.appendChild(el('li', 'empty', 'Chưa có hoạt động nào'));
        return;
    }
    data.logs.forEach(e => {
        const li = el('li');
        li.append(el('time', '', e.gio), document.createTextNode(' ' + e.nd));
        ul.appendChild(li);
    });
}

// ---------- Điều khiển giao diện ----------
function setView(v) {
    $('dash').dataset.view = v;
    document.querySelectorAll('.top nav a').forEach(a => a.classList.toggle('on', a.dataset.view === v));
}

document.querySelectorAll('.top nav a').forEach(a => {
    a.onclick = e => {
        e.preventDefault();
        setView(a.dataset.view);
    };
});
$('btnLogin').onclick = doLogin;
$('pw').onkeydown = e => {
    if (e.key === 'Enter')
        doLogin();
};
$('btnRefresh').onclick = () => {
    request();
    toast('Đã làm mới');
};
$('btnLogout').onclick = () => {
    authed = false;
    try {
        ws.close();   // quyền quản trị gắn với kết nối, đóng kết nối là mất quyền
    } catch (e) {
    }
    showLogin('');   // onclose tự nối lại bằng kết nối mới, chưa xác thực
};
$('roomQ').oninput = renderRooms;
$('playerQ').oninput = renderPlayers;

setStatus(false);
connect();
})();
