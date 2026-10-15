(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const el = (tag, cls, text) => {
        const e = document.createElement(tag);
        if (cls)
            e.className = cls;
        if (text !== undefined)
            e.textContent = text;
        return e;
    };

    let ws = null, pw = '', authed = false, poll = null, retry = null, data = null, toastT = null;

// ---------- Kết nối (cùng cầu nối /ws với trang người chơi) ----------
    function wsUrl() {
        return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + location.pathname.replace(/[^/]*$/, '') + 'ws';
    }
    function connect() {
        ws = new WebSocket(wsUrl());
        ws.onopen = () => {
            $('conn').className = 'dot on';
            if (pw)
                send('ADMIN_LOGIN', {password: pw});
        };
        ws.onclose = () => {
            $('conn').className = 'dot';
            authed = false;
            stopPoll();
            setStatus(false);
            clearTimeout(retry);
            retry = setTimeout(connect, 3000);
        };
        ws.onmessage = e => {
            try {
                handle(JSON.parse(e.data));
            } catch (err) { /* bỏ qua gói tin lỗi */
            }
        };
    }
    function send(type, payload) {
        if (ws && ws.readyState === 1)
            ws.send(JSON.stringify({type, payload: payload || {}}));
    }
    function toast(msg) {
        const t = $('toast');
        t.textContent = msg;
        t.classList.remove('hidden');
        clearTimeout(toastT);
        toastT = setTimeout(() => t.classList.add('hidden'), 3000);
    }
    function setStatus(ok) {
        const s = $('srvStatus');
        s.textContent = ok ? 'Máy chủ đang hoạt động' : 'Mất kết nối tới máy chủ';
        s.className = 'pill ' + (ok ? 'ok' : 'bad');
    }

// ---------- Nhận dữ liệu ----------
    function handle(m) {
        const p = m.payload || {};
        switch (m.type) {
            case 'ADMIN_OK':
                authed = true;
                setStatus(true);
                $('loginErr').textContent = '';
                $('pw').value = '';
                $('loginBox').classList.add('hidden');
                $('dash').classList.remove('hidden');
                $('btnLogout').classList.remove('hidden');
                startPoll();
                break;
            case 'ADMIN_FAIL':
                if (!authed) {   // sai mật khẩu (hoặc server đã đổi mật khẩu sau khi khởi động lại)
                    pw = '';
                    $('loginErr').textContent = p.thongBao || 'Đăng nhập thất bại';
                    $('dash').classList.add('hidden');
                    $('btnLogout').classList.add('hidden');
                    $('loginBox').classList.remove('hidden');
                } else
                    toast(p.thongBao || 'Thao tác thất bại');
                break;
            case 'ADMIN_DATA':
                data = p;
                render();
                break;
            case 'ERROR':
                toast(p.thongBao || 'Có lỗi xảy ra');
                break;
        }
    }
    function startPoll() {
        stopPoll();
        send('ADMIN_OVERVIEW');
        poll = setInterval(() => send('ADMIN_OVERVIEW'), 2000);
    }
    function stopPoll() {
        clearInterval(poll);
        poll = null;
    }

// ---------- Hiển thị ----------
    function fmtUp(sec) {
        const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
        if (d)
            return d + 'n ' + h + 'g';
        if (h)
            return h + 'g ' + m + 'p';
        return m + 'p ' + (sec % 60) + 's';
    }
    function has(text, kw) {
        return String(text || '').toLowerCase().includes(kw);
    }

    function render() {
        if (!data)
            return;
        const s = data.stats || {}, rooms = data.rooms || [], players = data.players || [];
        const inRoom = players.filter(x => x.roomId).length;
        $('stRooms').textContent = s.phong || 0;
        $('stRoomsSub').textContent = (s.dangThi || 0) + ' đang thi đấu';
        $('stOnline').textContent = s.online || 0;
        $('stOnlineSub').textContent = inRoom + ' trong phòng, ' + (players.length - inRoom) + ' ở sảnh';
        $('stCpu').textContent = (s.cpu || 0) + '%';
        $('stCpuSub').textContent = 'Bộ nhớ ' + (s.boNhoMB || 0) + ' MB · chạy ' + fmtUp(s.uptimeGiay || 0);
        const ok = typeof s.pingTB === 'number' && s.pingTB >= 0;
        $('stPing').textContent = ok ? s.pingTB + ' ms' : '\u2014';
        $('stPingSub').textContent = ok ? 'Đo từ ' + s.pingMau + ' người chơi' : 'Chưa có người chơi nào phản hồi';

        renderRooms(rooms);
        renderPlayers(players);
        renderLogs(data.logs || []);
    }

    function emptyRow(tbody, cols, text) {
        const tr = el('tr'), td = el('td', 'empty', text);
        td.colSpan = cols;
        tr.appendChild(td);
        tbody.appendChild(tr);
    }

    function renderRooms(rooms) {
        const kw = $('roomQ').value.trim().toLowerCase();
        const rows = rooms.filter(r => !kw || has(r.ten, kw) || has(r.roomId, kw) || has(r.tenBo, kw) || has(r.chuPhong, kw));
        $('roomCount').textContent = rooms.length;
        const tb = $('roomTable').tBodies[0];
        tb.replaceChildren();
        if (!rows.length) {
            emptyRow(tb, 6, rooms.length ? 'Không có phòng khớp tìm kiếm.' : 'Chưa có phòng nào.');
            return;
        }
        rows.forEach(r => {
            const tr = el('tr');
            const c1 = el('td');
            c1.append(el('div', 'cell-main', r.ten), el('div', 'cell-sub', 'Mã ' + r.roomId + (r.an ? ' · phòng ẩn' : '')));
            const c4 = el('td');
            c4.appendChild(el('span', 'pill ' + (r.dangChoi ? 'play' : 'ok'), r.dangChoi ? 'Đang thi đấu' : 'Đang chờ'));
            const c6 = el('td', 'act'), b = el('button', 'sm warn', 'Đóng phòng');
            b.type = 'button';
            b.onclick = () => {
                if (window.confirm('Đóng phòng ' + r.roomId + ' (' + r.ten + ')?\nMọi người trong phòng sẽ bị đưa về sảnh.'))
                    send('ADMIN_CLOSE_ROOM', {roomId: r.roomId});
            };
            c6.appendChild(b);
            tr.append(c1, el('td', '', r.chuPhong), el('td', '', r.soNguoi + '/' + r.toiDa), c4, el('td', '', r.tenBo), c6);
            tb.appendChild(tr);
        });
    }

    function renderPlayers(players) {
        const kw = $('playerQ').value.trim().toLowerCase();
        const rows = players.filter(x => !kw || has(x.username, kw) || has(x.roomId, kw) || has(x.roomTen, kw));
        $('playerCount').textContent = players.length;
        const tb = $('playerTable').tBodies[0];
        tb.replaceChildren();
        if (!rows.length) {
            emptyRow(tb, 4, players.length ? 'Không có người chơi khớp tìm kiếm.' : 'Chưa có người chơi nào online.');
            return;
        }
        rows.forEach(x => {
            const tr = el('tr');
            const c1 = el('td'), w = el('div', 'who-cell'), info = el('div');
            info.append(el('div', 'cell-main', x.username), el('div', 'cell-sub', 'ID ' + x.accountId));
            w.append(el('div', 'av', (x.username || '?').charAt(0).toUpperCase()), info);
            c1.appendChild(w);
            const c2 = el('td');
            if (x.roomId)
                c2.append(el('div', 'cell-main', x.roomTen), el('div', 'cell-sub', 'Mã ' + x.roomId));
            else
                c2.appendChild(el('span', 'cell-sub', 'Sảnh'));
            const c3 = el('td');
            c3.appendChild(el('span', 'pill ' + (x.roomId ? 'play' : 'ok'), x.roomId ? 'Trong phòng' : 'Ở sảnh'));
            const c4 = el('td', 'act');
            if (x.roomId) {
                const k = el('button', 'sm ghost', 'Đuổi khỏi phòng');
                k.type = 'button';
                k.onclick = () => {
                    if (window.confirm('Đuổi ' + x.username + ' khỏi phòng ' + x.roomId + '?'))
                        send('ADMIN_KICK_ROOM', {accountId: x.accountId});
                };
                c4.appendChild(k);
            }
            const d = el('button', 'sm warn', 'Ngắt kết nối');
            d.type = 'button';
            d.onclick = () => {
                if (window.confirm('Ngắt kết nối ' + x.username + ' khỏi server?'))
                    send('ADMIN_DISCONNECT', {accountId: x.accountId});
            };
            c4.appendChild(d);
            tr.append(c1, c2, c3, c4);
            tb.appendChild(tr);
        });
    }

    function renderLogs(logs) {
        const ul = $('logList');
        ul.replaceChildren();
        if (!logs.length) {
            ul.appendChild(el('li', 'empty', 'Chưa có hoạt động nào.'));
            return;
        }
        logs.forEach(l => {
            const li = el('li');
            li.append(el('time', '', l.gio), el('span', '', l.nd));
            ul.appendChild(li);
        });
    }

// ---------- Nút bấm ----------
    function login() {
        pw = $('pw').value;
        if (!pw) {
            $('loginErr').textContent = 'Hãy nhập mật khẩu';
            return;
        }
        $('loginErr').textContent = '';
        send('ADMIN_LOGIN', {password: pw});
    }
    $('btnLogin').onclick = login;
    $('pw').onkeydown = e => {
        if (e.key === 'Enter')
            login();
    };
    $('btnRefresh').onclick = () => send('ADMIN_OVERVIEW');
    $('roomQ').oninput = render;
    $('playerQ').oninput = render;
    $('btnLogout').onclick = () => {
        pw = '';
        authed = false;
        stopPoll();
        $('dash').classList.add('hidden');
        $('btnLogout').classList.add('hidden');
        $('loginBox').classList.remove('hidden');
        if (ws)
            ws.close();   // kết nối mới sẽ chưa xác thực
    };
    document.querySelectorAll('.top nav a').forEach(a => a.onclick = e => {
            e.preventDefault();
            $('dash').dataset.view = a.dataset.view;
            document.querySelectorAll('.top nav a').forEach(x => x.classList.toggle('on', x === a));
            window.scrollTo(0, 0);
        });

    connect();
})();