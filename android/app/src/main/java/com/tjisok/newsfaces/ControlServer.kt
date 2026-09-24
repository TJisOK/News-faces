package com.tjisok.newsfaces

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Tiny HTTP control panel served by the phone: open http://<phone-ip>:8080 from a computer on the same Wi-Fi. */
class ControlServer(private val app: AppState, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/state" -> json(state())
                uri == "/set" -> { session.parms.forEach { (k, v) -> app.applyParam(k, v) }; json(state()) }
                uri == "/record" -> { val eng = app.engine; if (session.parms["on"] == "1") eng.startRecording() else eng.stopRecording(); json(state()) }
                uri == "/arrange" -> { app.engine.arrange(); json(state()) }
                uri == "/still" -> { if (session.parms["off"] == "1") { app.still.value = null; app.engine.reset() } else app.useStill(session.parms["url"]); json(state()) }
                uri == "/snapshot.jpg" -> {
                    val bmp = app.engine.latestBitmap() ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                    val small = if (bmp.width > 540) Bitmap.createScaledBitmap(bmp, 540, bmp.height * 540 / bmp.width, true) else bmp
                    val bos = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 70, bos); val bytes = bos.toByteArray()
                    newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply { addHeader("Cache-Control", "no-store") }
                }
                else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
            }
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString()) }
    }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString()).apply { addHeader("Access-Control-Allow-Origin", "*"); addHeader("Cache-Control", "no-store") }

    private fun state(): JSONObject {
        val s = app.settings.value; val f = s.face; val eng = app.engine
        return JSONObject()
            .put("cols", f.cols).put("gap", f.gap).put("track", f.track).put("pad", f.pad).put("outline", f.outline).put("multi", f.multi).put("mirror", f.mirror)
            .put("hue", f.hue).put("sat", f.sat).put("bright", f.bright).put("contrast", f.contrast).put("tintAmt", f.tintAmt).put("tint", String.format("#%06X", f.tint and 0xFFFFFF))
            .put("match", f.match).put("variety", f.variety).put("stability", f.stability).put("fps", f.fps).put("freeze", f.freeze)
            .put("ageH", s.ageH).put("minSat", s.minSat).put("lightMin", s.lightMin).put("lightMax", s.lightMax).put("max", s.max).put("sort", s.sort)
            .put("photos", app.photos.value.size).put("pool", eng.poolSize).put("actualFps", eng.measuredFps).put("recording", eng.isRecording).put("recSeconds", eng.recordingSeconds)
            .put("headScale", f.headScale).put("extent", f.extent).put("anchor", f.anchor)
            .put("autoRes", f.autoRes).put("nearFrac", f.nearFrac).put("farFrac", f.farFrac).put("colsMin", f.colsMin).put("colsMax", f.colsMax).put("faceFrac", eng.faceFrac).put("colsInUse", eng.colsInUse)
            .put("still", app.still.value != null).put("imu", f.imu).put("imuRange", f.imuRange).put("imuSatMin", f.imuSatMin).put("imuSatMax", f.imuSatMax).put("roll", eng.roll).put("satInUse", eng.satInUse)
    }

    companion object {
        fun localIp(): String? {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces().toList().sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                for (ni in ifs) { if (!ni.isUp || ni.isLoopback) continue; for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress }
            } catch (_: Exception) {}
            return null
        }

        private val PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>News Faces control</title>
<style>body{margin:0;background:#0b0b0e;color:#f2f2f5;font:14px -apple-system,Inter,Segoe UI,sans-serif}.wrap{display:grid;grid-template-columns:minmax(280px,380px) 1fr;gap:20px;padding:20px;max-width:1100px;margin:auto}
h1{font-size:16px;margin:0 0 12px}h2{font-size:12px;color:#8e8ea0;text-transform:uppercase;letter-spacing:.5px;margin:18px 0 6px}.c{margin:6px 0 10px}.c label{display:flex;justify-content:space-between;color:#8e8ea0;font-size:12px}.c label output{color:#f2f2f5}
input[type=range]{width:100%;accent-color:#0a84ff}select,button{font:inherit;color:#f2f2f5;background:#1d1d25;border:1px solid #2a2a34;border-radius:8px;padding:6px 10px}button.on{background:#e0322f;border-color:#e0322f}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
img{width:100%;border-radius:12px;background:#000;display:block}.st{color:#8e8ea0;font-size:12px;margin-top:8px}label.ck{display:flex;gap:8px;align-items:center;margin:6px 0}@media(max-width:700px){.wrap{grid-template-columns:1fr}}</style></head><body><div class="wrap"><div>
<h1>News Faces · phone control</h1>
<div class="row"><button id="rec">● Record</button><button onclick="fetch('/arrange')">Arrange heads</button><label class="ck"><input type="checkbox" id="freeze"> Freeze</label></div>
<div class="row" style="margin-top:8px"><span style="color:#8e8ea0;font-size:12px">Source</span><button onclick="fetch('/still')">Random news photo</button><button onclick="fetch('/still?off=1')">Camera</button></div>
<div class="row" style="margin-top:8px"><span style="color:#8e8ea0;font-size:12px">Preset</span><button onclick="set('preset','Frame')">Frame</button><button onclick="set('preset','Head')">Head</button><button onclick="set('preset','Heads')">Heads</button></div>
<h2>Pixels</h2>
<div class="c"><label>Density (cells across) <output id="o-cols"></output></label><input type="range" id="cols" min="4" max="200" step="1"></div>
<div class="c"><label>Cell gap <output id="o-gap"></output></label><input type="range" id="gap" min="0" max="0.5" step="0.01"></div>
<div class="c"><label>Frame rate <output id="o-fps"></output></label><input type="range" id="fps" min="1" max="30" step="1"></div>
<label class="ck"><input type="checkbox" id="mirror"> Mirror</label>
<h2>Colour of the target (photos are never altered)</h2>
<div class="c"><label>Hue shift <output id="o-hue"></output></label><input type="range" id="hue" min="-180" max="180" step="1"></div>
<div class="c"><label>Saturation <output id="o-sat"></output></label><input type="range" id="sat" min="0" max="3" step="0.02"></div>
<div class="c"><label>Brightness <output id="o-bright"></output></label><input type="range" id="bright" min="-0.6" max="0.6" step="0.01"></div>
<div class="c"><label>Contrast <output id="o-contrast"></output></label><input type="range" id="contrast" min="0.2" max="3" step="0.02"></div>
<div class="c"><label>Tint <input type="color" id="tint"> <output id="o-tintAmt"></output></label><input type="range" id="tintAmt" min="0" max="1" step="0.01"></div>
<div class="c"><label>Match by</label><select id="match"><option value="avg">Average colour</option><option value="vivid">Most vivid colour</option></select></div>
<div class="c"><label>Variety <output id="o-variety"></output></label><input type="range" id="variety" min="0" max="1" step="0.01"></div>
<div class="c"><label>Stability <output id="o-stability"></output></label><input type="range" id="stability" min="0" max="1" step="0.01"></div>
<h2>Head mode</h2>
<div class="c"><label>Head size (also two-finger pinch on the phone) <output id="o-headScale"></output></label><input type="range" id="headScale" min="0.2" max="4" step="0.02"></div>
<div class="c"><label>Outline extent</label><select id="extent"><option value="face">Face only</option><option value="head">Head (hair to chin)</option><option value="neck">Head + neck</option><option value="bust">Head + shoulders</option></select></div>
<div class="c"><label>Anchor on screen</label><select id="anchor"><option value="center">Centre</option><option value="top">Top</option><option value="bottom">Bottom</option></select></div>
<h2>Resolution follows distance</h2>
<label class="ck"><input type="checkbox" id="autoRes"> Auto: near face → coarse, far face → fine</label>
<div class="c"><label>Near threshold (face height / frame) <output id="o-nearFrac"></output></label><input type="range" id="nearFrac" min="0.1" max="0.9" step="0.01"></div>
<div class="c"><label>Far threshold <output id="o-farFrac"></output></label><input type="range" id="farFrac" min="0.03" max="0.6" step="0.01"></div>
<div class="c"><label>Cells when near <output id="o-colsMin"></output></label><input type="range" id="colsMin" min="2" max="240" step="1"></div>
<div class="c"><label>Cells when far <output id="o-colsMax"></output></label><input type="range" id="colsMax" min="2" max="240" step="1"></div>
<div class="st" id="resLive"></div>
<h2>Tilt → saturation (IMU)</h2>
<label class="ck"><input type="checkbox" id="imu"> Left/right tilt of the phone drives saturation</label>
<div class="c"><label>Tilt range (°) <output id="o-imuRange"></output></label><input type="range" id="imuRange" min="5" max="90" step="1"></div>
<div class="c"><label>Saturation when tilted left <output id="o-imuSatMin"></output></label><input type="range" id="imuSatMin" min="0" max="3" step="0.02"></div>
<div class="c"><label>Saturation when tilted right <output id="o-imuSatMax"></output></label><input type="range" id="imuSatMax" min="0" max="3" step="0.02"></div>
<div class="st" id="imuLive"></div>
<h2>Head tracking</h2>
<label class="ck"><input type="checkbox" id="track"> Track heads (off = whole frame becomes pixels)</label>
<div class="c"><label>Outline</label><select id="outline"><option value="head">Head shape</option><option value="oval">Oval</option><option value="none">Full crop</option></select></div>
<label class="ck"><input type="checkbox" id="multi"> All faces in a grid</label>
<div class="c"><label>Face framing <output id="o-pad"></output></label><input type="range" id="pad" min="0.8" max="3" step="0.05"></div>
<h2>Which news photos</h2>
<div class="c"><label>Max age (hours) <output id="o-ageH"></output></label><input type="range" id="ageH" min="1" max="168" step="1"></div>
<div class="c"><label>Min saturation <output id="o-minSat"></output></label><input type="range" id="minSat" min="0" max="1" step="0.01"></div>
<div class="c"><label>Lightness min <output id="o-lightMin"></output></label><input type="range" id="lightMin" min="0" max="1" step="0.01"></div>
<div class="c"><label>Lightness max <output id="o-lightMax"></output></label><input type="range" id="lightMax" min="0" max="1" step="0.01"></div>
<div class="c"><label>Max photos <output id="o-max"></output></label><input type="range" id="max" min="50" max="4000" step="50"></div>
</div><div><img id="snap" alt="live preview"><div class="st" id="st"></div></div></div>
<script>
const ids=['cols','gap','fps','hue','sat','bright','contrast','tintAmt','variety','stability','pad','ageH','minSat','lightMin','lightMax','max','headScale','nearFrac','farFrac','colsMin','colsMax','imuRange','imuSatMin','imuSatMax'];
const cks=['mirror','freeze','track','multi','autoRes','imu'];const sels=['match','outline','extent','anchor'];let busy=0;
function show(s){for(const k of ids){const el=document.getElementById(k);if(document.activeElement!==el)el.value=s[k];const o=document.getElementById('o-'+k);if(o)o.textContent=(+s[k]).toFixed(['cols','fps','ageH','max','hue','colsMin','colsMax','imuRange'].includes(k)?0:2);}
document.getElementById('resLive').textContent='live: face height '+(s.faceFrac*100).toFixed(0)+'% of frame → '+s.colsInUse+' cells across';document.getElementById('imuLive').textContent='live: roll '+s.roll.toFixed(1)+'° → saturation ×'+s.satInUse.toFixed(2);
for(const k of cks)document.getElementById(k).checked=!!s[k];for(const k of sels)document.getElementById(k).value=s[k];document.getElementById('tint').value=s.tint;
const r=document.getElementById('rec');r.className=s.recording?'on':'';r.textContent=s.recording?'■ Stop '+Math.floor(s.recSeconds/60).toString().padStart(2,'0')+':'+(s.recSeconds%60).toString().padStart(2,'0'):'● Record';
document.getElementById('st').textContent=s.photos+' photos · pool '+s.pool+' · '+s.actualFps.toFixed(1)+' fps';}
async function set(k,v){busy++;try{show(await (await fetch('/set?'+k+'='+encodeURIComponent(v))).json());}finally{busy--;}}
for(const k of ids)document.getElementById(k).addEventListener('input',e=>set(k,e.target.value));
for(const k of cks)document.getElementById(k).addEventListener('change',e=>set(k,e.target.checked?1:0));
for(const k of sels)document.getElementById(k).addEventListener('change',e=>set(k,e.target.value));
document.getElementById('tint').addEventListener('input',e=>set('tint',e.target.value));
document.getElementById('rec').addEventListener('click',async()=>{const s=await (await fetch('/state')).json();show(await (await fetch('/record?on='+(s.recording?0:1))).json());});
async function poll(){if(!busy){try{show(await (await fetch('/state')).json());}catch(e){}}setTimeout(poll,1000);}poll();
const img=document.getElementById('snap');function snap(){img.src='/snapshot.jpg?t='+Date.now();}img.onload=()=>setTimeout(snap,150);img.onerror=()=>setTimeout(snap,800);snap();
</script></body></html>"""
    }
}
