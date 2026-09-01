(function () {
  const canvas = document.getElementById('starfield');
  if (!canvas) return;
  const context = canvas.getContext('2d');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let stars = [];
  let width = 0;
  let height = 0;
  let pixelRatio = 1;

  function resize() {
    pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    width = window.innerWidth;
    height = window.innerHeight;
    canvas.width = width * pixelRatio;
    canvas.height = height * pixelRatio;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    const count = Math.min(150, Math.max(65, Math.floor(width * height / 13000)));
    stars = Array.from({ length: count }, (_, index) => ({
      x: Math.random() * width,
      y: Math.random() * height,
      radius: Math.random() * 1.5 + .25,
      alpha: Math.random() * .65 + .15,
      phase: Math.random() * Math.PI * 2,
      speed: Math.random() * .0006 + .00015,
      tint: index % 7 === 0 ? 'cyan' : index % 11 === 0 ? 'pink' : 'white'
    }));
  }

  function draw(time) {
    context.clearRect(0, 0, width, height);
    stars.forEach((star) => {
      const pulse = reduceMotion.matches ? 1 : .72 + Math.sin(time * star.speed + star.phase) * .28;
      const alpha = star.alpha * pulse;
      const color = star.tint === 'cyan' ? `82,231,255` : star.tint === 'pink' ? `243,91,216` : `222,231,255`;
      context.beginPath();
      context.fillStyle = `rgba(${color},${alpha})`;
      context.shadowBlur = star.radius > 1 ? 10 : 0;
      context.shadowColor = `rgba(${color},${alpha})`;
      context.arc(star.x, star.y, star.radius * (star.tint === 'white' ? 1 : 1.2), 0, Math.PI * 2);
      context.fill();
    });
    context.shadowBlur = 0;
    if (!reduceMotion.matches) requestAnimationFrame(draw);
  }

  resize();
  window.addEventListener('resize', resize, { passive: true });
  requestAnimationFrame(draw);

  const release = {
    badgeVersion: document.getElementById('release-badge-version'),
    version: document.getElementById('release-version'),
    description: document.getElementById('release-description'),
    size: document.getElementById('release-size'),
    sha: document.getElementById('release-sha'),
    hash: document.getElementById('release-hash'),
    link: document.getElementById('download-link'),
    notesTitle: document.getElementById('release-notes-title'),
    notesList: document.getElementById('release-notes-list'),
    published: document.getElementById('release-published'),
    proof: document.getElementById('release-proof-text')
  };

  function formatBytes(value) {
    if (!Number.isFinite(value) || value <= 0) return 'APK';
    return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  }

  function shortHash(value) {
    return value && value.length > 16 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
  }

  function isSafeDownloadUrl(value) {
    if (typeof value !== 'string' || value.trim() === '') return false;
    const url = value.trim();
    return !url.startsWith('//') && (url.startsWith('/') || url.startsWith('./') || url.startsWith('https://'));
  }

  async function loadRelease() {
    const response = await fetch(`release.json?ts=${Date.now()}`, {
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    });
    if (!response.ok) throw new Error(`release manifest returned ${response.status}`);
    const data = await response.json();
    if (!Number.isInteger(data.versionCode) || typeof data.versionName !== 'string') {
      throw new Error('release manifest is incomplete');
    }

    const name = `MeshGram ${data.versionName}`;
    const hash = typeof data.apkSha256 === 'string' ? data.apkSha256.toLowerCase() : '';
    const notes = Array.isArray(data.changelog) ? data.changelog.filter(Boolean).slice(0, 6) : [];

    if (release.badgeVersion) release.badgeVersion.textContent = name;
    if (release.version) release.version.textContent = data.versionName;
    if (release.notesTitle) release.notesTitle.textContent = name;
    if (release.description && notes[0]) release.description.textContent = notes[0];
    if (release.size) release.size.textContent = formatBytes(Number(data.sizeBytes));
    if (release.sha && hash) {
      release.sha.textContent = `SHA-256: ${shortHash(hash)}`;
      release.sha.title = hash;
    }
    if (release.hash && hash) release.hash.textContent = shortHash(hash);
    if (release.link && isSafeDownloadUrl(data.file)) release.link.href = data.file;
    if (release.published) release.published.textContent = `GitHub Pages / main / v${data.versionCode}`;
    if (release.proof) release.proof.textContent = 'Манифест обновления проверен';
    if (release.notesList && notes.length) {
      release.notesList.replaceChildren(...notes.map((note) => {
        const item = document.createElement('li');
        item.textContent = note;
        return item;
      }));
    }
  }

  loadRelease().catch(() => {
    if (release.proof) release.proof.textContent = 'Манифест временно недоступен';
  });
})();
