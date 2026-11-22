#!/usr/bin/env bash
# تشغيل المحاكاة: يحتاج JDK/JRE 17+ و kotlinc. من جذر المشروع:  bash dev-tests/run-sim.sh
set -e
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
$KOTLINC app/src/main/java/com/futureroots/solarmonitor/core/*.kt dev-tests/Sim.kt -include-runtime -d /tmp/solar-sim.jar 2>&1 | grep -v -E "warning|URL\(|\^|^\s*$|Picked up" || true
java -cp /tmp/solar-sim.jar SimKt
# مقارنة تنسيق الأرقام مع JavaScript الحقيقي (يجب أن تتطابق حرفياً مع ما يحسبه n8n)
node -e '
const xs=[0,5,12.3,12.34,0.05,0.15,1.25,1.35,2.675,100,1234567.8,0.1+0.2,99.95,5.55];
const out=xs.map(x=>String(Number(x.toFixed(1)))).join("\n");
const kt=require("fs").readFileSync("/tmp/kt_numbers.txt","utf8");
console.log(out===kt?"PASS تنسيق الأرقام Kotlin == JavaScript ("+xs.length+" عينة)":"FAIL تنسيق الأرقام\nJS:\n"+out+"\nKT:\n"+kt);
process.exit(out===kt?0:1)'
