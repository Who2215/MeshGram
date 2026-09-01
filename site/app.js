(function () {
  const en = {
    nav_aria: 'Main navigation', nav_how: 'How it works', nav_security: 'Privacy', nav_releases: 'Releases', nav_download: 'Download', nav_try: 'Try it', language_label: 'Language',
    hero_eyebrow: 'Next-generation mesh connection', hero_title: 'Messages find', hero_title_em: 'their way.', hero_slogan: 'Tired of choosing a VPN? MeshGram finds the route.', hero_lead: 'MeshGram connects people through nearby devices over BLE and uses an encrypted internet fallback for long-distance routes.', hero_download: 'Download MeshGram', hero_explore: 'Understand it in a minute', trust_android: 'Android 6+', trust_encryption: 'E2E encryption', latest_release: 'Latest release',
    signal_1: 'We look for<br>the nearest BLE route', signal_2: 'If it is unavailable,<br>we use a relay', signal_3: 'The content stays<br>encrypted', how_eyebrow: 'Route under control', how_title: 'Do not choose between', how_title_em: 'near', how_title_tail: 'and far.', feature_ble_title: 'BLE priority', feature_ble_body: 'When MeshGram devices are nearby, messages use the local mesh network. The app does not disable internet on your phone or interfere with other apps.', feature_crypto_title: 'Only participants can read', feature_crypto_body: 'Frames pass through nodes like encrypted envelopes. A relay sees only a technical packet, never the text or files.', feature_storage_title: 'Local storage', feature_storage_body: 'History and delivery queues stay in protected device storage. Encrypted export is available for migration.',
    quote_first: 'We are not building another server,', quote_second: 'we are building a ', quote_em: 'route.', current_release: 'Current release', download_description: 'BLE-first hybrid routing, relay fallback, live visuals, and a responsive interface for Android 6 and newer.', download_apk: 'Download APK', manifest_verified: 'Update manifest verified', manifest_unavailable: 'Manifest temporarily unavailable', updates_feedback: 'Updates and feedback', telegram_channel: 'Official Telegram channel', soon: 'soon', channel_pending: 'The link will appear after the project owner creates the channel.', creator_kicker: 'Support the creator', creator_title: 'Help MeshGram grow', creator_body: 'Your support helps test devices, ship updates, and keep building communication without borders.', creator_button: 'Support the developer', release_eyebrow: 'Project pulse', release_title: 'Every release is visible', release_title_em: 'down to the last line.', whats_new: 'What is new', verifiable_file: 'Verifiable file', verifiable_body: 'The APK is published with SHA-256 and a signed manifest. The app accepts only a verified version.', footer_tagline: 'Connection through the space between nodes', back_top: 'Back to top ↑', manifest_pending: 'The update manifest is loading.',
    metaDescription: 'MeshGram is a protected messenger with BLE mesh and an encrypted internet fallback.', ogTitle: 'MeshGram | Connection without borders', ogDescription: 'BLE first. The internet is only a fallback route for distant contacts.', pageTitle: 'MeshGram | Connection without borders'
  };
  const ru = {
    nav_aria: 'Основная навигация', nav_how: 'Как работает', nav_security: 'Приватность', nav_releases: 'Релизы', nav_download: 'Скачать', nav_try: 'Попробовать', language_label: 'Язык', hero_eyebrow: 'Mesh-связь нового поколения', hero_title: 'Сообщения находят', hero_title_em: 'свой путь.', hero_slogan: 'Надоело выбирать VPN? MeshGram сам ищет маршрут.', hero_lead: 'MeshGram соединяет людей через ближайшие устройства по BLE, а для дальних маршрутов использует зашифрованный интернет-резерв.', hero_download: 'Скачать MeshGram', hero_explore: 'Разобраться за минуту', trust_android: 'Android 6+', trust_encryption: 'E2E-шифрование', latest_release: 'Последний релиз',
    signal_1: 'Сначала ищем<br>ближайший BLE-путь', signal_2: 'Если его нет,<br>подключаем relay', signal_3: 'Содержимое остаётся<br>зашифрованным', how_eyebrow: 'Маршрут под контролем', how_title: 'Не выбирай между', how_title_em: 'близко', how_title_tail: 'и далеко.', feature_ble_title: 'BLE-приоритет', feature_ble_body: 'Когда рядом есть устройства MeshGram, сообщения идут по локальной mesh-сети. Приложение не отключает интернет в телефоне и не вмешивается в другие приложения.', feature_crypto_title: 'Читают только участники', feature_crypto_body: 'Кадры проходят через узлы как зашифрованные конверты. Relay видит только технический пакет, не текст и не файлы.', feature_storage_title: 'Локальное хранение', feature_storage_body: 'История и очередь доставки остаются в защищённом хранилище устройства. Для миграции предусмотрен зашифрованный экспорт.',
    quote_first: 'Мы не строим ещё один сервер,', quote_second: 'мы строим ', quote_em: 'маршрут.', current_release: 'Текущий релиз', download_description: 'Гибридная маршрутизация BLE-first, fallback relay, живой фон и адаптивный интерфейс для Android 6 и новее.', download_apk: 'Скачать APK', manifest_verified: 'Манифест обновления проверен', manifest_unavailable: 'Манифест временно недоступен', updates_feedback: 'Обновления и обратная связь', telegram_channel: 'Официальный Telegram-канал', soon: 'скоро', channel_pending: 'Ссылка появится после создания канала владельцем проекта.', creator_kicker: 'Создателю MeshGram', creator_title: 'Поддержать развитие проекта', creator_body: 'Поддержка помогает тестировать устройства, выпускать обновления и развивать связь без границ.', creator_button: 'Поддержать разработчика', release_eyebrow: 'Пульс проекта', release_title: 'Каждый релиз виден', release_title_em: 'до последней строки.', whats_new: 'Что нового', verifiable_file: 'Проверяемый файл', verifiable_body: 'APK публикуется вместе с SHA-256 и манифестом. Приложение принимает только проверенную версию.', footer_tagline: 'Связь через пространство между узлами', back_top: 'Наверх ↑', manifest_pending: 'Загрузка манифеста обновления.',
    metaDescription: 'MeshGram — защищённый мессенджер с BLE mesh и интернет-резервом.', ogTitle: 'MeshGram | Связь без границ', ogDescription: 'Сначала BLE. Интернет только как резервный маршрут для дальних контактов.', pageTitle: 'MeshGram | Связь без границ'
  };
  const core = {
    es: ['Navegación principal', 'Cómo funciona', 'Privacidad', 'Versiones', 'Descargar', 'Probar', 'Idioma', 'Conexión mesh de nueva generación', 'Los mensajes encuentran', 'su camino.', 'La conexión no tiene que estar cerca.', 'Descargar MeshGram', 'Última versión', 'Cifrado E2E', 'Actualizaciones y comentarios', 'Apoya al creador', 'Ayuda a crecer a MeshGram', 'Apoyar al desarrollador', 'Volver arriba ↑'],
    de: ['Hauptnavigation', 'So funktioniert es', 'Privatsphäre', 'Versionen', 'Download', 'Ausprobieren', 'Sprache', 'Mesh-Verbindung der nächsten Generation', 'Nachrichten finden', 'ihren Weg.', 'Verbindung muss nicht in der Nähe sein.', 'MeshGram herunterladen', 'Neueste Version', 'E2E-Verschlüsselung', 'Updates und Feedback', 'Unterstütze den Entwickler', 'Hilf MeshGram zu wachsen', 'Entwickler unterstützen', 'Nach oben ↑'],
    fr: ['Navigation principale', 'Fonctionnement', 'Confidentialité', 'Versions', 'Télécharger', 'Essayer', 'Langue', 'Connexion mesh nouvelle génération', 'Les messages trouvent', 'leur chemin.', "La connexion n'a pas besoin d'être proche.", 'Télécharger MeshGram', 'Dernière version', 'Chiffrement E2E', 'Mises à jour et retours', 'Soutenir le créateur', 'Aidez MeshGram à grandir', 'Soutenir le développeur', 'Retour en haut ↑'],
    pt: ['Navegação principal', 'Como funciona', 'Privacidade', 'Versões', 'Baixar', 'Experimentar', 'Idioma', 'Conexão mesh de nova geração', 'As mensagens encontram', 'seu caminho.', 'A conexão não precisa estar perto.', 'Baixar MeshGram', 'Versão mais recente', 'Criptografia E2E', 'Atualizações e feedback', 'Apoie o criador', 'Ajude o MeshGram a crescer', 'Apoiar o desenvolvedor', 'Voltar ao topo ↑'],
    it: ['Navigazione principale', 'Come funziona', 'Privacy', 'Versioni', 'Scarica', 'Prova', 'Lingua', 'Connessione mesh di nuova generazione', 'I messaggi trovano', 'la loro strada.', 'La connessione non deve essere vicina.', 'Scarica MeshGram', 'Ultima versione', 'Crittografia E2E', 'Aggiornamenti e feedback', 'Sostieni il creatore', 'Aiuta MeshGram a crescere', 'Sostieni lo sviluppatore', 'Torna su ↑'],
    tr: ['Ana navigasyon', 'Nasıl çalışır', 'Gizlilik', 'Sürümler', 'İndir', 'Dene', 'Dil', 'Yeni nesil mesh bağlantısı', 'Mesajlar kendi', 'yolunu bulur.', 'Bağlantının yakınında olmasına gerek yok.', 'MeshGram indir', 'Son sürüm', 'Uçtan uca şifreleme', 'Güncellemeler ve geri bildirim', 'Geliştiriciyi destekle', "MeshGram'ın büyümesine yardım et", 'Geliştiriciyi destekle', 'Başa dön ↑'],
    zh: ['主导航', '工作方式', '隐私', '版本', '下载', '立即体验', '语言', '新一代 Mesh 连接', '让消息找到', '自己的路。', '连接不必近在身边。', '下载 MeshGram', '最新版本', '端到端加密', '更新与反馈', '支持创作者', '帮助 MeshGram 成长', '支持开发者', '返回顶部 ↑'],
    ja: ['メインナビゲーション', '仕組み', 'プライバシー', 'リリース', 'ダウンロード', '試す', '言語', '次世代のメッシュ通信', 'メッセージは', '道を見つける。', 'つながりは近くになくてもいい。', 'MeshGram をダウンロード', '最新リリース', 'E2E 暗号化', '更新とフィードバック', '開発者を支援', 'MeshGram の成長を支える', '開発者を支援', 'トップへ戻る ↑'],
    ko: ['기본 탐색', '작동 방식', '개인정보', '릴리스', '다운로드', '사용해 보기', '언어', '차세대 메시 네트워크', '메시지는', '길을 찾습니다.', '연결은 가까이 있을 필요가 없습니다.', 'MeshGram 다운로드', '최신 릴리스', '종단 간 암호화', '업데이트 및 피드백', '개발자 후원', 'MeshGram의 성장을 도와주세요', '개발자 후원', '맨 위로 ↑'],
    ar: ['التنقل الرئيسي', 'كيف يعمل', 'الخصوصية', 'الإصدارات', 'تنزيل', 'جرّبه', 'اللغة', 'اتصال شبكي من الجيل الجديد', 'الرسائل تجد', 'طريقها.', 'لا يشترط أن يكون الاتصال قريباً.', 'تنزيل MeshGram', 'أحدث إصدار', 'تشفير من الطرف إلى الطرف', 'التحديثات والملاحظات', 'ادعم المطوّر', 'ساعد MeshGram على النمو', 'ادعم المطوّر', 'العودة إلى الأعلى ↑'],
    hi: ['मुख्य नेविगेशन', 'यह कैसे काम करता है', 'गोपनीयता', 'रिलीज़', 'डाउनलोड', 'आजमाएँ', 'भाषा', 'नई पीढ़ी का मेश कनेक्शन', 'संदेश अपना', 'रास्ता खोज लेते हैं।', 'कनेक्शन का पास होना ज़रूरी नहीं।', 'MeshGram डाउनलोड करें', 'नवीनतम रिलीज़', 'E2E एन्क्रिप्शन', 'अपडेट और प्रतिक्रिया', 'निर्माता का समर्थन करें', 'MeshGram को आगे बढ़ाने में मदद करें', 'डेवलपर का समर्थन करें', 'ऊपर जाएँ ↑']
  };
  const coreKeys = ['nav_aria', 'nav_how', 'nav_security', 'nav_releases', 'nav_download', 'nav_try', 'language_label', 'hero_eyebrow', 'hero_title', 'hero_title_em', 'hero_slogan', 'hero_download', 'latest_release', 'trust_encryption', 'updates_feedback', 'creator_kicker', 'creator_title', 'creator_button', 'back_top'];
  const translations = { en, ru };
  Object.entries(core).forEach(([language, values]) => { translations[language] = { ...en }; coreKeys.forEach((key, index) => { translations[language][key] = values[index]; }); });
  const slogans = {
    es: '¿Cansado de elegir una VPN? MeshGram encuentra la ruta.',
    de: 'Keine Lust mehr, VPNs zu wählen? MeshGram findet die Route.',
    fr: 'Marre de choisir un VPN ? MeshGram trouve la route.',
    pt: 'Cansado de escolher uma VPN? O MeshGram encontra a rota.',
    it: 'Stanco di scegliere una VPN? MeshGram trova il percorso.',
    tr: 'VPN seçmekten sıkıldın mı? MeshGram rotayı bulur.',
    zh: '厌倦了选择 VPN？MeshGram 会找到路径。',
    ja: 'VPN 選びに疲れた？MeshGram が経路を見つけます。',
    ko: 'VPN 선택에 지치셨나요? MeshGram이 경로를 찾습니다.',
    ar: 'هل سئمت من اختيار VPN؟ يجد MeshGram المسار.',
    hi: 'VPN चुनते-चुनते थक गए? MeshGram रास्ता खोजता है।'
  };
  Object.entries(slogans).forEach(([language, slogan]) => { translations[language].hero_slogan = slogan; });

  function storedLanguage() { try { return localStorage.getItem('meshgram-language') || 'auto'; } catch (_) { return 'auto'; } }
  function normalizeLanguage(value) { const short = String(value || '').toLowerCase().slice(0, 2); return translations[short] ? short : 'en'; }
  function effectiveLanguage(selection) { return selection === 'auto' ? normalizeLanguage(navigator.language || navigator.userLanguage || 'en') : normalizeLanguage(selection); }
  function applyLanguage(selection) {
    const language = effectiveLanguage(selection);
    const dictionary = translations[language] || en;
    document.documentElement.lang = language;
    document.documentElement.dir = language === 'ar' ? 'rtl' : 'ltr';
    document.querySelectorAll('[data-i18n]').forEach((element) => { const key = element.getAttribute('data-i18n'); if (dictionary[key]) element.innerHTML = dictionary[key]; });
    document.querySelectorAll('[data-i18n-aria-label]').forEach((element) => { const key = element.getAttribute('data-i18n-aria-label'); if (dictionary[key]) element.setAttribute('aria-label', dictionary[key]); });
    const meta = document.getElementById('meta-description');
    const ogTitle = document.getElementById('meta-og-title');
    const ogDescription = document.getElementById('meta-og-description');
    const pageTitle = document.getElementById('page-title');
    if (meta) meta.content = dictionary.metaDescription;
    if (ogTitle) ogTitle.content = dictionary.ogTitle;
    if (ogDescription) ogDescription.content = dictionary.ogDescription;
    if (pageTitle) pageTitle.textContent = dictionary.pageTitle;
    document.title = dictionary.pageTitle;
    const select = document.getElementById('language-select');
    if (select) select.value = selection === 'auto' ? 'auto' : language;
  }
  const initialLanguage = storedLanguage();
  applyLanguage(initialLanguage);
  const languageSelect = document.getElementById('language-select');
  if (languageSelect) languageSelect.addEventListener('change', () => { const next = languageSelect.value; try { localStorage.setItem('meshgram-language', next); } catch (_) {} applyLanguage(next); });

  const canvas = document.getElementById('starfield');
  const context = canvas && canvas.getContext ? canvas.getContext('2d') : null;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let stars = [], width = 0, height = 0, pixelRatio = 1;
  function resize() {
    if (!canvas || !context) return;
    pixelRatio = Math.min(window.devicePixelRatio || 1, 2); width = window.innerWidth; height = window.innerHeight;
    canvas.width = width * pixelRatio; canvas.height = height * pixelRatio; canvas.style.width = width + 'px'; canvas.style.height = height + 'px'; context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    const count = Math.min(170, Math.max(70, Math.floor(width * height / 12500)));
    stars = Array.from({ length: count }, (_, index) => ({ x: Math.random() * width, y: Math.random() * height, radius: Math.random() * 1.5 + .25, alpha: Math.random() * .65 + .15, phase: Math.random() * Math.PI * 2, speed: Math.random() * .00045 + .00008, drift: (Math.random() - .5) * .012, tint: index % 7 === 0 ? 'cyan' : index % 11 === 0 ? 'pink' : 'white' }));
  }
  function draw(time) {
    if (!canvas || !context) return;
    context.clearRect(0, 0, width, height);
    stars.forEach((star) => { if (!reduceMotion.matches) { star.x += star.drift; if (star.x < -4) star.x = width + 4; if (star.x > width + 4) star.x = -4; } const pulse = reduceMotion.matches ? 1 : .72 + Math.sin(time * star.speed + star.phase) * .28; const alpha = star.alpha * pulse; const color = star.tint === 'cyan' ? '82,231,255' : star.tint === 'pink' ? '243,91,216' : '222,231,255'; context.beginPath(); context.fillStyle = `rgba(${color},${alpha})`; context.shadowBlur = star.radius > 1 ? 10 : 0; context.shadowColor = `rgba(${color},${alpha})`; context.arc(star.x, star.y, star.radius * (star.tint === 'white' ? 1 : 1.2), 0, Math.PI * 2); context.fill(); });
    context.shadowBlur = 0; if (!reduceMotion.matches) requestAnimationFrame(draw);
  }
  if (canvas && context) { resize(); window.addEventListener('resize', resize, { passive: true }); requestAnimationFrame(draw); }

  const release = { badgeVersion: document.getElementById('release-badge-version'), version: document.getElementById('release-version'), size: document.getElementById('release-size'), sha: document.getElementById('release-sha'), hash: document.getElementById('release-hash'), link: document.getElementById('download-link'), notesTitle: document.getElementById('release-notes-title'), notesList: document.getElementById('release-notes-list'), published: document.getElementById('release-published'), proof: document.getElementById('release-proof-text') };
  function formatBytes(value) { return !Number.isFinite(value) || value <= 0 ? 'APK' : `${(value / (1024 * 1024)).toFixed(1)} MB`; }
  function shortHash(value) { return value && value.length > 16 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value; }
  function isSafeDownloadUrl(value) { if (typeof value !== 'string' || value.trim() === '') return false; const url = value.trim(); return !url.startsWith('//') && (url.startsWith('/') || url.startsWith('./') || url.startsWith('https://')); }
  async function loadRelease() {
    const response = await fetch(`release.json?ts=${Date.now()}`, { cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`release manifest returned ${response.status}`);
    const data = await response.json();
    if (!Number.isInteger(data.versionCode) || typeof data.versionName !== 'string') throw new Error('release manifest is incomplete');
    const name = `MeshGram ${data.versionName}`; const hash = typeof data.apkSha256 === 'string' ? data.apkSha256.toLowerCase() : ''; const notes = Array.isArray(data.changelog) ? data.changelog.filter(Boolean).slice(0, 6) : [];
    if (release.badgeVersion) release.badgeVersion.textContent = name; if (release.version) release.version.textContent = data.versionName; if (release.notesTitle) release.notesTitle.textContent = name; if (release.size) release.size.textContent = formatBytes(Number(data.sizeBytes));
    if (release.sha && hash) { release.sha.textContent = `SHA-256: ${shortHash(hash)}`; release.sha.title = hash; } if (release.hash && hash) release.hash.textContent = shortHash(hash); if (release.link && isSafeDownloadUrl(data.file)) release.link.href = data.file; if (release.published) release.published.textContent = `GitHub Pages / main / v${data.versionCode}`;
    const dictionary = translations[effectiveLanguage(storedLanguage())] || en; if (release.proof) release.proof.textContent = dictionary.manifest_verified;
    if (release.notesList && notes.length) release.notesList.replaceChildren(...notes.map((note) => { const item = document.createElement('li'); item.textContent = note; return item; }));
  }
  loadRelease().catch(() => { const dictionary = translations[effectiveLanguage(storedLanguage())] || en; if (release.proof) release.proof.textContent = dictionary.manifest_unavailable; });
})();
