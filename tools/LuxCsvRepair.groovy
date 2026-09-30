/**
 *  Lux CSV Repair (one-off utility)
 *
 *  Rebuilds a Rule Machine "Lux Logger" CSV that was written with %nl% (rendered as the
 *  literal text "null") and 12-hour hh:mm times without an AM/PM marker.
 *
 *  Output: header "date,time,device,value", one record per line, 24-hour HH:mm:00 times.
 *  AM/PM is recovered per calendar day by monotonic ordering, and the starting half of
 *  each day is chosen by checking the outdoor sensor against daylight (no daylight at
 *  night, no darkness at midday).
 *
 *  Runs entirely on the hub: downloadHubFile -> transform -> uploadHubFile.
 *  Set "Run mode" to analyze (report only) or repair (backup + rewrite), then press Done.
 *
 *  Author: Ashwin (with Claude)   Version: 1.0
 */

definition(
    name: "Lux CSV Repair",
    namespace: "jungalow",
    author: "Ashwin",
    description: "One-off: rebuild lux_clean.csv with real newlines and 24-hour times",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Lux CSV Repair", install: true, uninstall: true) {
        section("Files") {
            input "srcFile", "text", title: "Source file (File Manager name)", defaultValue: "lux_clean.csv", required: true
            input "backupFile", "text", title: "Untouched raw backup name (repair mode only)", defaultValue: "lux_clean_raw_backup_20260930.csv", required: true
            input "outFile", "text", title: "Output file name", defaultValue: "lux_clean.csv", required: true
            input "outdoorName", "text", title: "Outdoor sensor device name (daylight check)", defaultValue: "Outdoor Sensor", required: true
        }
        section("Run") {
            input "runMode", "enum", title: "Run mode (job starts 2 s after Done)", options: ["idle": "Idle", "analyze": "Analyze only (no writes)", "repair": "Repair (backup + rewrite)"], defaultValue: "idle", required: true, submitOnChange: true
            paragraph "Status: ${state.status ?: 'never run'}"
            paragraph "Report:<br><pre style='white-space:pre-wrap'>${state.report ?: '(none)'}</pre>"
        }
    }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    String mode = settings.runMode ?: "idle"
    if (mode != "idle") {
        state.status = "queued (${mode}) at ${new Date().format('HH:mm:ss')}"
        runIn(2, "runRepair", [data: [mode: mode]])
    }
}

def runRepair(data) {
    String mode = data?.mode ?: (settings.runMode ?: "idle")
    // reset the selector first so a later Done never re-runs by accident
    app.updateSetting("runMode", [type: "enum", value: "idle"])
    if (mode == "idle") return
    if (state.running) { log.warn "Lux CSV Repair: already running, skipping"; return }
    state.running = true
    state.status = "running (${mode}) since ${new Date().format('HH:mm:ss')}"
    try {
        long t0 = now()
        byte[] raw = downloadHubFile(settings.srcFile)
        if (raw == null || raw.length == 0) throw new Exception("downloadHubFile(${settings.srcFile}) returned nothing")
        String text = new String(raw, "UTF-8")

        // Records are joined by the literal text "null" (RM rendered %nl% that way).
        // Anything already newline-terminated (post-fix rows) is split too.
        List<String> pieces = []
        text.split("null").each { String chunk ->
            chunk.split("\n").each { String rec ->
                String r = rec.trim()
                if (r) pieces << r
            }
        }
        if (pieces && pieces[0] == "date,time,device,value") pieces.remove(0)
        int textLen = text.length()
        text = null   // free the 2x copy early; the hub is memory-constrained

        // Parse
        List recs = []
        int badFields = 0
        pieces.each { String p ->
            String[] f = p.split(",", -1)
            if (f.length >= 4 && f[0] ==~ /\d{4}-\d{2}-\d{2}/ && f[1] ==~ /\d{1,2}:\d{2}(:\d{2})?/) {
                String[] hm = f[1].split(":")
                int h = Integer.parseInt(hm[0])
                int m = Integer.parseInt(hm[1])
                boolean is24 = (hm.length == 3) || h == 0 || h > 12   // already-fixed rows carry seconds
                String dev = f.length == 4 ? f[2] : f[2..(f.length - 2)].join(",")
                recs << [d: f[0], h: h, m: m, is24: is24, dev: dev, v: f[f.length - 1]]
            } else {
                badFields++
                recs << [raw: p, bad: true]
            }
        }

        pieces = null

        // Group into contiguous runs of the same date and assign 24h minutes
        StringBuilder rep = new StringBuilder()
        int i = 0
        int total = recs.size()
        int anomalies = 0
        while (i < total) {
            if (recs[i].bad) { i++; continue }
            String d = recs[i].d
            int j = i
            while (j < total && (recs[j].bad || recs[j].d == d)) j++
            List run = []
            for (int k = i; k < j; k++) if (!recs[k].bad) run << recs[k]
            // Try both starting halves; lower key wins (order anomalies weigh more than daylight contradictions), tie -> AM
            Map am = assignRun(run, 0)
            Map pm = assignRun(run, 1)
            int keyAm = am.bad * 1000 + am.score
            int keyPm = pm.bad * 1000 + pm.score
            Map best = (keyPm < keyAm) ? pm : am
            for (int k = 0; k < run.size(); k++) run[k].min24 = best.mins[k]
            anomalies += best.bad
            int first = best.mins[0]
            int last = best.mins[-1]
            rep.append(d).append(" n=").append(run.size())
               .append(" start=").append(best.is(pm) ? "PM" : "AM")
               .append(" ").append(fmt(first)).append("-").append(fmt(last))
               .append(" bad=").append(best.bad).append(" score=").append(best.score)
               .append(" keys=AM:").append(keyAm).append("/PM:").append(keyPm)
               .append("\n")
            i = j
        }

        // Build output
        StringBuilder out = new StringBuilder(textLen + total * 4 + 64)
        out.append("date,time,device,value\n")
        recs.each { r ->
            if (r.bad) { out.append(r.raw).append("\n") }
            else {
                out.append(r.d).append(",").append(fmt(r.min24)).append(":00,").append(r.dev).append(",").append(r.v).append("\n")
            }
        }

        String summary = "mode=${mode} inBytes=${raw.length} records=${total} badFields=${badFields} orderAnomalies=${anomalies} outChars=${out.length()} ms=${now() - t0}"
        if (mode == "repair") {
            uploadHubFile(settings.backupFile, raw)
            uploadHubFile(settings.outFile, out.toString().getBytes("UTF-8"))
            summary += " | wrote ${settings.backupFile} (${raw.length} B) and ${settings.outFile} (${out.length()} B)"
        }
        state.report = summary + "\n" + rep.toString()
        state.status = "done (${mode}) at ${new Date().format('HH:mm:ss')}"
        log.info "Lux CSV Repair: ${summary}"
    } catch (e) {
        state.status = "FAILED: ${e}"
        log.error "Lux CSV Repair failed: ${e}"
    } finally {
        state.running = false
    }
}

// Assign 24-hour minutes to a run of same-date records in file order.
// half: 0 = first record is AM, 1 = first record is PM. Returns [mins, bad, score].
Map assignRun(List run, int half) {
    List<Integer> mins = []
    int prev = -1
    int bad = 0
    int score = 0
    String outdoor = (settings.outdoorName ?: "Outdoor Sensor").toString()
    run.each { r ->
        int c
        if (r.is24) {
            c = r.h * 60 + r.m
        } else {
            int base = (r.h % 12) * 60 + r.m
            if (prev < 0) c = base + half * 720
            else {
                int a = base, p = base + 720
                if (a >= prev - 3) c = a
                else if (p >= prev - 3) c = p
                else { c = p; bad++ }
            }
        }
        mins << c
        prev = c
        if (r.dev == outdoor) {
            Integer lux = null
            try { lux = (r.v as BigDecimal).intValue() } catch (ignored) { }
            if (lux != null) {
                if ((c < 300 || c >= 1260) && lux >= 50) score++      // daylight before 05:00 or after 21:00
                if (c >= 600 && c < 960 && lux < 10) score++          // darkness between 10:00 and 16:00
            }
        }
    }
    return [mins: mins, bad: bad, score: score]
}

String fmt(int mins) {
    int h = (mins.intdiv(60)) % 24
    int m = mins % 60
    return String.format("%02d:%02d", h, m)
}
