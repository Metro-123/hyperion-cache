/* Hyperion cache console — browser edition (no server, runs the JS cache port) */
(() => {
    "use strict";

    const $ = (id) => document.getElementById(id);
    let autoRefresh = true;
    let pollTimer = null;
    let labScenario = "scan";
    let cache = Hyperion.createCache(1000, 0);

    // ------------------------------------------------------------ helpers

    function toast(message, isError) {
        const el = $("toast");
        el.textContent = message;
        el.classList.toggle("err", !!isError);
        el.classList.add("show");
        clearTimeout(el._t);
        el._t = setTimeout(() => el.classList.remove("show"), 2600);
    }

    function snapshot() {
        return { stats: cache.stats(), diagnostics: cache.diagnostics(), entries: cache.snapshot() };
    }

    const shown = new Map();
    function count(el, target) {
        const key = el.id;
        const from = shown.has(key) ? shown.get(key) : 0;
        shown.set(key, target);
        el.textContent = fmt(target);
        if (from === target || document.hidden || !document.hasFocus()) return;
        const start = performance.now();
        const dur = 500;
        function step(now) {
            const t = Math.min(1, (now - start) / dur);
            const eased = 1 - Math.pow(1 - t, 3);
            el.textContent = fmt(from + (target - from) * eased);
            if (t < 1) requestAnimationFrame(step);
            else el.textContent = fmt(target);
        }
        requestAnimationFrame(step);
    }

    function fmt(n) {
        if (!isFinite(n)) return "0";
        return Math.round(n).toLocaleString("en-US");
    }

    function pct(n) {
        return (n * 100).toFixed(1) + "%";
    }

    function ttlText(ms) {
        if (ms < 0) return { text: "∞", cls: "none" };
        if (ms < 1000) return { text: Math.round(ms) + " ms", cls: "" };
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

        const hr = s.requestCount === 0 ? 0 : s.hitRate;
        $("statHitRate").textContent = pct(hr);
        $("statHits").textContent = fmt(s.hitCount) + " hits / " + fmt(s.requestCount) + " reqs";
        $("gaugeArc").style.strokeDashoffset = String(327 * (1 - hr));
        $("gaugePct").textContent = pct(hr);

        $("sparkSize").style.width = Math.min(100, (d.estimatedWeight / Math.max(1, d.maximumWeight)) * 100) + "%";
        $("sparkHit").style.width = (hr * 100) + "%";

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

    function refresh() {
        // process due expirations so the numbers stay honest while idle
        cache.cleanUp();
        render(snapshot());
    }

    function setAuto(on) {
        autoRefresh = on;
        $("liveBadge").classList.toggle("paused", !on);
        $("liveBadge").innerHTML = '<span class="dot"></span> ' + (on ? "live" : "paused");
        $("refreshToggle").textContent = on ? "⏸ auto" : "▶ auto";
        clearInterval(pollTimer);
        if (on) pollTimer = setInterval(refresh, 1000);
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

    $("cleanupBtn").addEventListener("click", () => {
        const fired = cache.cleanUp();
        render(snapshot());
        toast("cleanUp() expired " + fired + " entr" + (fired === 1 ? "y" : "ies"));
    });

    $("putBtn").addEventListener("click", () => {
        const key = $("putKey").value.trim();
        if (!key) return toast("Enter a key", true);
        cache.put(key, $("putValue").value, Number($("putTtl").value) || 0);
        render(snapshot());
        toast('put("' + key + '")');
    });

    $("putRandom").addEventListener("click", () => {
        $("putKey").value = "random:" + Math.random().toString(36).slice(2, 8);
        $("putValue").value = "v" + Math.floor(Math.random() * 1000);
        $("putTtl").value = "";
    });

    $("getBtn").addEventListener("click", () => {
        const key = $("getKey").value.trim();
        if (!key) return toast("Enter a key", true);
        const value = cache.getIfPresent(key);
        render(snapshot());
        const box = $("getResult");
        if (value !== null) {
            box.className = "result ok";
            box.textContent = "HIT  " + key + " → " + value;
        } else {
            box.className = "result miss";
            box.textContent = "MISS " + key + " (absent or expired)";
        }
        toast(value !== null ? "hit" : "miss");
    });

    $("invalidateBtn").addEventListener("click", () => {
        const key = $("getKey").value.trim();
        if (!key) return toast("Enter a key", true);
        cache.invalidate(key);
        render(snapshot());
        toast('invalidate("' + key + '")');
    });

    $("cfgBtn").addEventListener("click", () => {
        const size = Number($("cfgSize").value) || 1000;
        const ttl = Number($("cfgTtl").value) || 0;
        cache = Hyperion.createCache(size, ttl);
        render(snapshot());
        toast("Cache rebuilt with capacity " + size);
    });

    $("clearBtn").addEventListener("click", () => {
        cache.invalidateAll();
        render(snapshot());
        toast("invalidateAll() — cache wiped");
    });

    // ------------------------------------------------------------ lab

    document.querySelectorAll(".seg-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".seg-btn").forEach((b) => b.classList.remove("active"));
            btn.classList.add("active");
            labScenario = btn.dataset.scenario;
        });
    });

    $("runLab").addEventListener("click", () => {
        const btn = $("runLab");
        btn.disabled = true;
        btn.textContent = "running…";
        // defer so the button state paints before the synchronous benchmark runs
        setTimeout(() => {
            try {
                const data = Hyperion.runSimulation(labScenario, Number($("labCapacity").value) || 1000);
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
                    $("labCard" + i).classList.remove("winner", "loser");
                });
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
        }, 20);
    });

    // ------------------------------------------------------------ seed + boot

    (function seed() {
        const keys = ["session:alice", "session:bob", "config:features", "config:limits",
            "user:1", "user:2", "user:3", "product:sku-100", "product:sku-200", "rate:global"];
        keys.forEach((k, i) => cache.put(k, "value-" + i));
        for (let r = 0; r < 25; r++) {
            cache.getIfPresent("session:alice");
            cache.getIfPresent("config:features");
            cache.getIfPresent("user:1");
        }
        cache.put("session:ephemeral", "expires-soon", 30000);
        cache.put("otp:987654", "123456", 120000);
    })();

    setAuto(true);
    refresh();
})();
