// 댓글 위젯: 방송·다시보기 상세 화면에서 공용으로 씁니다.
//   const c = chiComments.mount(element, { kind: 'broadcasts' | 'replays', id, ownerOf: () => owner })
//   c.onPush(message)  ← 방송 중 WebSocket 으로 받은 {type:'comment', action, ...}
// 수정·삭제 버튼은 화면 편의로만 보이거나 숨기며, 허용 여부는 서버가 다시 검사합니다.
window.chiComments = {
  mount(root, { kind, id, ownerOf }) {
    const listUrl = `/api/${kind}/${id}/comments`;
    let nextBefore = null;
    const items = new Map(); // id -> comment

    root.innerHTML = `
      <div class="section-head"><h2>댓글</h2><p class="meta" data-count></p></div>
      <form class="comment-form" data-form>
        <textarea maxlength="500" rows="2" data-input placeholder="댓글을 입력하세요 (최대 500자)"></textarea>
        <button class="btn btn-primary" type="submit">등록</button>
      </form>
      <p class="meta" data-login hidden>댓글을 쓰려면 <a class="accent" href="${chi.loginUrl()}">로그인</a>하세요.</p>
      <p class="error" data-error></p>
      <ul class="comments" data-list></ul>
      <button class="btn" type="button" data-more hidden>이전 댓글 더 보기</button>`;
    const $ = (sel) => root.querySelector(`[data-${sel}]`);

    chi.ready.then(() => {
      $('form').hidden = !chi.me;
      $('login').hidden = !!chi.me;
    });

    function canEdit(c) { return chi.isMe(c.author); }
    function canDelete(c) { return chi.isMe(c.author) || chi.isMe(ownerOf()); }

    function render(c) {
      const li = document.createElement('li');
      li.className = 'comment';
      li.dataset.id = c.id;
      const owner = ownerOf();
      const badge = owner && c.author && owner.id === c.author.id ? '<span class="tag">스트리머</span>' : '';
      li.innerHTML = `
        <div class="comment-head">
          <b>${chi.esc(c.author?.nickname || '알 수 없음')}</b>${badge}
          <span class="meta">${chi.ago(c.createdAt)}${c.updatedAt ? ' · 수정됨' : ''}</span>
          <span class="comment-actions">
            ${canEdit(c) ? '<button type="button" class="link" data-act="edit">수정</button>' : ''}
            ${canDelete(c) ? '<button type="button" class="link" data-act="delete">삭제</button>' : ''}
          </span>
        </div>
        <div class="comment-body"></div>`;
      li.querySelector('.comment-body').textContent = c.content;
      return li;
    }

    function upsert(c, { prepend = false } = {}) {
      items.set(c.id, c);
      const list = $('list');
      const old = list.querySelector(`[data-id="${c.id}"]`);
      const li = render(c);
      if (old) old.replaceWith(li);
      else if (prepend) list.prepend(li);
      else list.append(li);
      updateCount();
    }

    function remove(commentId) {
      items.delete(commentId);
      root.querySelector(`[data-id="${commentId}"]`)?.remove();
      updateCount();
    }

    function updateCount() {
      $('count').textContent = items.size ? `${items.size}개${nextBefore ? '+' : ''}` : '';
    }

    async function load() {
      const q = nextBefore ? `?before=${nextBefore}` : '';
      const page = await chi.api(listUrl + q);
      page.comments.forEach((c) => upsert(c)); // 최신순으로 아래에 이어 붙임
      nextBefore = page.nextBefore;
      $('more').hidden = !nextBefore;
      updateCount();
    }

    $('more').onclick = () => load().catch((e) => { $('error').textContent = e.message; });

    $('form').onsubmit = async (e) => {
      e.preventDefault();
      $('error').textContent = '';
      const content = $('input').value.trim();
      if (!content) return;
      try {
        const c = await chi.api(listUrl, { method: 'POST', json: { content } });
        $('input').value = '';
        upsert(c, { prepend: true });
      } catch (err) {
        $('error').textContent = err.status === 401 ? '로그인이 필요합니다.' : err.message;
      }
    };

    $('list').onclick = async (e) => {
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      const li = btn.closest('.comment');
      const c = items.get(Number(li.dataset.id));
      $('error').textContent = '';
      try {
        if (btn.dataset.act === 'delete') {
          if (!confirm('댓글을 삭제할까요?')) return;
          await chi.api(`/api/comments/${c.id}`, { method: 'DELETE' });
          remove(c.id);
        } else {
          const content = prompt('댓글 수정', c.content);
          if (content == null || !content.trim()) return;
          upsert(await chi.api(`/api/comments/${c.id}`, { method: 'PATCH', json: { content } }));
        }
      } catch (err) {
        $('error').textContent = err.message;
      }
    };

    // 소유자 정보가 늦게 오거나 로그인 상태가 확정되면 버튼 표시를 다시 계산
    const rerender = () => [...items.values()].forEach((c) => upsert(c));
    chi.ready.then(rerender);

    load().catch((e) => { $('error').textContent = e.message; });

    return {
      rerender,
      onPush(m) {
        if (m.type !== 'comment') return;
        if (m.action === 'deleted') remove(m.commentId);
        else upsert(m.comment, { prepend: m.action === 'created' && !items.has(m.comment.id) });
      },
    };
  },
};
