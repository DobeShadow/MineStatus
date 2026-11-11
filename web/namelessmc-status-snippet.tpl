{*
  ============================================================
  NamelessMC status.tpl 插入片段
  ============================================================
  插入位置：custom/templates/DefaultRevamp/status.tpl
           在 {include file='footer.tpl'} 之前

  ⚠️ 关键：Smarty 用 { } 作为标签定界符，
     内联 CSS/JS 里的花括号必须用 {literal}...{/literal} 包起来，
     否则整页会报「致命错误」。
  ============================================================
*}

<div id="hm-online-players" style="display:none;">
    <h3>在线玩家</h3>
    <div id="hm-heads"></div>
</div>

{literal}
<style>
#hm-online-players {
    margin: 20px 0;
    padding: 16px;
    background: rgba(0, 0, 0, 0.03);
    border-radius: 8px;
}
#hm-online-players h3 {
    margin: 0 0 12px 0;
    font-size: 16px;
}
#hm-heads {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
}
.hm-head {
    display: inline-flex;
    flex-direction: column;
    align-items: center;
    width: 56px;
}
.hm-head img {
    width: 48px;
    height: 48px;
    border-radius: 8px;
    object-fit: cover;
    image-rendering: pixelated;
    background: #eee;
}
.hm-head span {
    font-size: 11px;
    margin-top: 3px;
    max-width: 56px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: center;
}
</style>

<script>
(function () {
    var container = document.getElementById('hm-online-players');
    var headsBox = document.getElementById('hm-heads');
    if (!container || !headsBox) return;

    var FALLBACK = '/wiki/favicon.png';

    function render(players) {
        headsBox.innerHTML = '';
        players.forEach(function (p) {
            var wrap = document.createElement('span');
            wrap.className = 'hm-head';
            wrap.title = p.name;

            var img = document.createElement('img');
            img.src = p.head || FALLBACK;
            img.alt = p.name;
            img.loading = 'lazy';
            img.onerror = function () {
                if (img.src.indexOf(FALLBACK) === -1) img.src = FALLBACK;
            };

            var label = document.createElement('span');
            label.textContent = p.name;

            wrap.appendChild(img);
            wrap.appendChild(label);
            headsBox.appendChild(wrap);
        });
    }

    function poll() {
        fetch('/status-cache.json?t=' + Date.now(), { cache: 'no-store' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                var players = (data && Array.isArray(data.players)) ? data.players : [];
                if (data && data.online && players.length > 0) {
                    render(players);
                    container.style.display = '';
                } else {
                    container.style.display = 'none';
                }
            })
            .catch(function () {
                container.style.display = 'none';
            });
    }

    poll();
    setInterval(poll, 15000);
})();
</script>
{/literal}
