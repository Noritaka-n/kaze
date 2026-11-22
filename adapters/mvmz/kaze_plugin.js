


(function () {
    var net = null;
    try {
        net = require('net');
    } catch (e) {
        console.log('[kaze] node context unavailable', e);
        return;
    }

    var PORT = KAZE_PORT;
    var socket = null;
    var buffer = '';
    var retry = null;

    function send(obj) {
        if (socket) {
            try { socket.write(JSON.stringify(obj) + '\n'); } catch (e) {  }
        }
    }

    function handle(line) {
        var msg;
        try { msg = JSON.parse(line); } catch (e) { return; }
        var value = null;
        try {
            
            value = eval(msg.expr);
        } catch (err) {
            value = JSON.stringify({ ok: false, detail: String(err) });
        }
        send({ id: msg.id, value: value });
    }

    function connect() {
        retry = null;
        
        
        
        if (socket && socket.destroyed === false) return;

        var conn;
        try {
            conn = net.connect({ host: '127.0.0.1', port: PORT }, function () {
                var engine = (typeof Utils !== 'undefined' && Utils.RPGMAKER_NAME) || 'MV';
                send({ hello: 'kaze', engine: engine });
            });
        } catch (e) {
            scheduleRetry();
            return;
        }
        socket = conn;

        conn.setEncoding('utf8');
        conn.on('data', function (chunk) {
            buffer += chunk;
            var index;
            while ((index = buffer.indexOf('\n')) >= 0) {
                var line = buffer.slice(0, index);
                buffer = buffer.slice(index + 1);
                if (line.trim()) handle(line);
            }
        });
        conn.on('error', function () { if (socket === conn) socket = null; scheduleRetry(); });
        conn.on('close', function () { if (socket === conn) socket = null; scheduleRetry(); });
    }

    function scheduleRetry() {
        socket = null;
        if (retry) return;
        retry = setTimeout(connect, 2000);
    }

    function boot() {
        if (typeof Scene_Boot !== 'undefined' && Scene_Boot.prototype) {
            var original = Scene_Boot.prototype.start;
            Scene_Boot.prototype.start = function () {
                original.call(this);
                connect();
            };
        } else {
            connect();
        }
    }

    boot();
    console.log('[kaze] bridge loaded, target port ' + PORT);
})();
