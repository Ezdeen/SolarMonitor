// يولّد رمز تفعيل لصاحب مشروع:  node tools/make-activation.mjs config.json
// config.json: { "name":"مصنع س", "n8n":"https://…/webhook/…", "key":"…", "eco":"https://…", "ecoKey":"…",
//                "audits":["https://…|key"], "ssid":"Factory-WiFi", "tz":"Asia/Jerusalem", "interval":60 }
// الناتج نص يبدأ بـ ECO1. — اطبعه كـ QR بأي مولّد QR (أو أرسله نصاً). أعطِ كل عميل مفتاحاً خاصاً به يمكن إبطاله من n8n.
import fs from 'node:fs'
const c = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
for (const k of ['n8n', 'key', 'eco', 'ecoKey']) if (!c[k]) { console.error('ناقص: ' + k); process.exit(2) }
console.log('ECO1.' + Buffer.from(JSON.stringify({ v: 1, ...c }), 'utf8').toString('base64url'))
