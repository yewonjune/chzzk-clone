// 후원 위젯: 방송·다시보기 화면에서 후원 합계·내역을 보여주고 후원 창을 엽니다.
//   const d = chiDonations.mount(element, { kind: 'broadcasts' | 'replays', id, ownerOf: () => owner })
//   d.onPush(message)  ← 방송 중 WebSocket 으로 받은 {type:'donation', donation, total, count}
// 화면에 보이는 합계·내역은 서버가 결제 승인을 확인해 확정(PAID)한 후원만입니다.
window.chiDonations = {
  mount(root, { kind, id, ownerOf, canDonate = true }) {
    const type = kind === 'broadcasts' ? 'broadcast' : 'replay';
    const seen = new Set();

    root.innerHTML = `
      <div class="section-head"><h2>후원</h2><p class="meta" data-total></p>
        <button class="btn btn-donate" type="button" data-donate hidden>♥ 후원하기</button></div>
      <p class="meta" data-hint></p>
      <p class="error" data-error></p>
      <ul class="donations" data-list></ul>`;
    const $ = (sel) => root.querySelector(`[data-${sel}]`);

    function setTotal(total, count) {
      $('total').innerHTML = count
        ? `합계 <b class="won">${chi.won(total)}</b> · ${Number(count).toLocaleString('ko-KR')}건`
        : '아직 후원이 없습니다.';
    }

    function render(d) {
      const li = document.createElement('li');
      li.className = 'donation';
      li.dataset.id = d.id;
      li.innerHTML = `
        <div class="donation-head">
          <b class="won">${chi.won(d.amount)}</b>
          <span>${chi.esc(d.donor?.nickname || '알 수 없음')}</span>
          <span class="meta">${d.paidAt ? chi.ago(d.paidAt) : ''}</span>
        </div>
        <div class="donation-msg"></div>`;
      li.querySelector('.donation-msg').textContent = d.message || '';
      return li;
    }

    function add(d, { prepend = false } = {}) {
      if (seen.has(d.id)) return false;
      seen.add(d.id);
      const li = render(d);
      if (prepend) $('list').prepend(li); else $('list').append(li);
      return true;
    }

    // 후원 버튼: 본인 방송이 아니면 표시 (본인 후원은 서버도 400 으로 거절)
    function updateButton() {
      const owner = ownerOf();
      const mine = chi.isMe(owner);
      $('donate').hidden = !canDonate || !owner || mine;
      $('hint').textContent = !owner ? '' : mine ? '내 방송에 들어온 후원입니다.' : '';
    }
    chi.ready.then(updateButton);

    $('donate').onclick = () => {
      const url = `/donate.html?type=${type}&id=${id}`;
      if (!chi.me) { location.href = `/auth.html?next=${encodeURIComponent(url)}`; return; }
      // 시청을 끊지 않도록 새 창(탭)에서 결제를 진행합니다.
      const w = window.open(url, '_blank');
      if (!w) location.href = url;
    };

    async function load() {
      const s = await chi.api(`/api/${kind}/${id}/donations`);
      setTotal(s.total, s.count);
      s.donations.forEach((d) => add(d));
    }
    load().catch((e) => { $('error').textContent = e.message; });

    return {
      updateButton,
      onPush(m) {
        if (m.type !== 'donation') return;
        setTotal(m.total, m.count);
        if (add(m.donation, { prepend: true })) chiDonations.toast(m.donation);
      },
    };
  },

  /** 방송 화면 위에 잠깐 띄우는 후원 알림 */
  toast(d) {
    const el = document.createElement('div');
    el.className = 'donation-toast';
    el.innerHTML = `<b>${chi.esc(d.donor?.nickname || '')}</b> 님이 <b class="won">${chi.won(d.amount)}</b> 후원!<div class="donation-msg"></div>`;
    el.querySelector('.donation-msg').textContent = d.message || '';
    document.body.append(el);
    setTimeout(() => el.classList.add('out'), 4500);
    setTimeout(() => el.remove(), 5200);
  },
};
