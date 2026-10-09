/* Hyperion cache console — zero-dependency vanilla JS */
(() => {
    "use strict";

    const $ = (id) => document.getElementById(id);
    let autoRefresh = true;
    let pollTimer = null;
    let labScenario = "scan";

    // ------------------------------------------------------------ helpers

    function toast(message, isError) {
        const el = $("toast");
        el.textContent = message;
        el.classList.toggle("err", !!isError);
        el.classList.add("show");
        clearTimeout(el._t);
        el._t = setTimeout(() => el.classList.remove("show"), 2600);
    }

    async function api(path, options) {
        const res = await fetch(path, options);
        const text = await res.text();
        let data;
        try {
            data = text ? JSON.parse(text) : {};
        } catch (e) {
            throw new Error("bad response: " + text.slice(0, 120));
        }
        if (data && data.error) {
            throw new Error(data.error);
        }
        return data;
    }

    const post = (path, body) => api(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body || {})
    });

    // animated numeric counters
    const shown = new Map();
    function count(el, target) {
        const key = el.id;
        const from = shown.has(key) ? shown.get(key) : 0;
        shown.set(key, target);
        // Always write the authoritative value first, so a throttled/hidden tab
        // (where requestAnimationFrame does not fire) still shows correct numbers.
        el.textContent = fmt(target);
        if (from === target || document.hidden || !document.hasFocus()) {
            return;
        }
        const start = performance.now();
        const dur = 500;
        function step(now) {
            const t = Math.min(1, (now - start) / dur);
            const eased = 1 - Math.pow(1 - t, 3);
            const cur = from + (target - from) * eased;
            el.textContent = fmt(cur);
            if (t < 1) {
                requestAnimationFrame(step);
            } else {
                el.textContent = fmt(target);
            }
        }
        requestAnimationFrame(step);
    }

    function fmt(n) {
        if (!isFinite(n)) return "0";
        const r = Math.round(n);
        return r.toLocaleString("en-US");
    }

    function pct(n) {
        return (n * 100).toFixed(1) + "%";
    }

    function ttlText(ms) {
        if (ms < 0) return { text: "∞", cls: "none" };
        if (ms < 1000) return { text: ms + " ms", cls: "" };
        return { text: (ms / 1000).toFixed(1) + " s", cls: "" };
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, (c) => ({
            "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
        }[c]));
    }

    // ------------------------------------------------------------ render

    function render(state) {
        const s = state.stats;
        const d = state.diagnostics;

        count($("statSize"), s.size);
        $("statWeight").textContent = "weight " + fmt(d.estimatedWeight) + " / " + fmt(d.maximumWeight);
        count($("statEvictions"), s.evictionCount);
        count($("statExpirations"), s.expirationCount);
        count($("statLoads"), s.loadSuccessCount);
        $("statLoadFail").textContent = s.loadFailureCount + " failed";
        $("statTimers").textContent = d.scheduledTimers + " timers scheduled";

        // hit rate + gauge
        const hr = s.requestCount === 0 ? 0 : s.hitRate;
        $("statHitRate").textContent = pct(hr);
        $("statHits").textContent = fmt(s.hitCount) + " hits / " + fmt(s.requestCount) + " reqs";
        const circumference = 327;
        $("gaugeArc").style.strokeDashoffset = String(circumference * (1 - hr));
        $("gaugePct").textContent = pct(hr);

        // sparks
        $("sparkSize").style.width = Math.min(100, (d.estimatedWeight / Math.max(1, d.maximumWeight)) * 100) + "%";
        $("sparkHit").style.width = (hr * 100) + "%";

        // segments
        $("segWindow").textContent = fmt(d.windowSize);
        $("segProbation").textContent = fmt(d.probationSize);
        $("segProtected").textContent = fmt(d.protectedSize);
        const totalW = Math.max(1, d.estimatedWeight);
        $("barWindow").style.width = (d.windowWeight / totalW * 100) + "%";
        $("barProbation").style.width = (d.probationWeight / totalW * 100) + "%";
        $("barProtected").style.width = (d.protectedWeight / totalW * 100) + "%";

        renderEntries(state.entries);
    }

    function renderEntries(entries) {
        const body = $("entriesBody");
        const max = 500;
        const slice = entries.slice(0, max);
        let html = "";
        for (const e of slice) {
            const t = ttlText(e.ttlRemainingMs);
            html += "<tr>" +
                '<td class="mono">' + escapeHtml(e.key) + "</td>" +
                '<td class="mono">' + escapeHtml(e.value) + "</td>" +
                '<td><span class="pill ' + e.segment + '">' + e.segment + "</span></td>" +
                "<td>" + e.weight + "</td>" +
                '<td class="ttl ' + t.cls + '">' + t.text + "</td>" +
                "</tr>";
        }
        if (!entries.length) {
            html = '<tr><td colspan="5" style="text-align:center;color:#6f7aa6;padding:24px">' +
                "Cache is empty. Head to the Playground to put() something.</td></tr>";
        }
        body.innerHTML = html;
        $("entryCount").textContent = entries.length + " shown" +
            (entries.length > max ? " (first " + max + ")" : "");
    }

    // ------------------------------------------------------------ polling

    async function refresh() {
        try {
            const state = await api("/api/state");
            render(state);
        } catch (e) {
            toast("Refresh failed: " + e.message, true);
        }
    }

    function setAuto(on) {
        autoRefresh = on;
        $("liveBadge").classList.toggle("paused", !on);
        $("liveBadge").innerHTML = '<span class="dot"></span> ' + (on ? "live" : "paused");
        $("refreshToggle").textContent = on ? "⏸ auto" : "▶ auto";
        clearInterval(pollTimer);
        if (on) {
            pollTimer = setInterval(refresh, 1000);
        }
    }

    // ------------------------------------------------------------ tabs

    document.querySelectorAll(".tab").forEach((tab) => {
        tab.addEventListener("click", () => {
            document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
            document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
            tab.classList.add("active");
            $("tab-" + tab.dataset.tab).classList.add("active");
        });
    });

    // ------------------------------------------------------------ actions

    $("refreshToggle").addEventListener("click", () => setAuto(!autoRefresh));

    $("cleanupBtn").addEventListener("click", async () => {
        try {
            const state = await post("/api/cleanup", {});
            render(state);
            toast("cleanUp() advanced the timer wheel");
        } catch (e) {
            toast(e.message, true);
        }
    });

    $("putBtn").addEventListener("click", async () => {
        const key = $("putKey").value.trim();
        if (!key) return toast("Enter a key", true);
        try {
            const state = await post("/api/put", {
                key,
                value: $("putValue").value,
                ttlMs: Number($("putTtl").value) || 0
            });
            render(state);
            toast('put("' + key + '")');
        } catch (e) {
            toast(e.message, true);
        }
    });

    $("putRandom").addEventListener("click", () => {
        $("putKey").value = "random:" + Math.random().toString(36).slice(2, 8);
        $("putValue").value = "v" + Math.floor(Math.random() * 1000);
        $("putTtl").value = "";
    });

    $("getBtn").addEventListener("click", async () => {
        const key = $("getKey").value.trim();
        if (!key) return toast("Enter a key", true);
        try {
            const data = await api("/api/get?key=" + encodeURIComponent(key));
            render(data.state);
            const box = $("getResult");
            if (data.found) {
                box.className = "result ok";
                box.textContent = "HIT  " + data.key + " → " + data.value;
            } else {
                box.className = "result miss";
                box.textContent = "MISS " + data.key + " (absent or expired)";
            }
            toast(data.found ? "hit" : "miss");
        } catch (e) {
            toast(e.message, true);
        }
    });

    $("invalidateBtn").addEventListener("click", async () => {
        const key = $("getKey").value.trim();
        if (!key) return toast("Enter a key", true);
        try {
            const state = await post("/api/invalidate", { key });
            render(state);
            toast('invalidate("' + key + '")');
        } catch (e) {
            toast(e.message, true);
        }
    });

    $("cfgBtn").addEventListener("click", async () => {
        try {
            const state = await post("/api/config", {
                maximumSize: Number($("cfgSize").value) || 1000,
                defaultTtlMs: Number($("cfgTtl").value) || 0
            });
            render(state);
            toast("Cache rebuilt with new capacity");
        } catch (e) {
            toast(e.message, true);
        }
    });

    $("clearBtn").addEventListener("click", async () => {
        try {
            const state = await post("/api/clear", {});
            render(state);
            toast("invalidateAll() — cache wiped");
        } catch (e) {
            toast(e.message, true);
        }
    });

    // ------------------------------------------------------------ lab

    document.querySelectorAll(".seg-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".seg-btn").forEach((b) => b.classList.remove("active"));
            btn.classList.add("active");
            labScenario = btn.dataset.scenario;
        });
    });

    $("runLab").addEventListener("click", async () => {
        const btn = $("runLab");
        btn.disabled = true;
        btn.textContent = "running…";
        try {
            const data = await post("/api/simulate", {
                scenario: labScenario,
                capacity: Number($("labCapacity").value) || 1000
            });
            $("labBlurb").textContent = data.blurb;
            const results = data.results || [];
            const primary = (r) => (r.hotSize > 0 ? r.survivalRate : r.hitRate);
            results.forEach((r, i) => {
                $("labName" + i).textContent = r.name;
                $("labHit" + i).textContent = pct(primary(r));
                $("labLabel" + i).textContent = r.hotSize > 0 ? "hot keys retained" : "lookup hit rate";
                $("labBar" + i).style.width = (primary(r) * 100) + "%";
                let detail = fmt(r.hits) + " hits · " + fmt(r.misses) + " misses" +
                    " · lookup hit rate " + pct(r.hitRate);
                if (r.hotSize > 0) {
                    detail += "\nkept " + r.hotSurvivors + "/" + r.hotSize +
                        " hot keys (" + pct(r.survivalRate) + " survival)";
                }
                $("labDetail" + i).textContent = detail;
                const card = $("labCard" + i);
                card.classList.remove("winner", "loser");
            });
            // winner = higher primary metric
            const win = primary(results[0]) >= primary(results[1]) ? 0 : 1;
            $("labCard" + win).classList.add("winner");
            $("labCard" + (1 - win)).classList.add("loser");
            toast("Benchmark complete");
        } catch (e) {
            toast("Benchmark failed: " + e.message, true);
        } finally {
            btn.disabled = false;
            btn.textContent = "▶ run benchmark";
        }
    });

    // ------------------------------------------------------------ boot

    setAuto(true);
    refresh();
})();
