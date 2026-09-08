import * as THREE from './assets/world/three.module.min.js';

// A local, dependency-free Three.js world: no user location data and no network
// request are needed for the visual. The photo/illustration fallback remains
// available when WebGL is unavailable or motion is reduced.
const root = document.getElementById('world-journey');
const canvas = document.getElementById('mesh-world');
const status = document.getElementById('world-status');
const toggle = document.getElementById('world-toggle');

const worldCopy = {
  en: {
    sender: 'Sender', relay: 'Relay', receiver: 'Recipient', connect: 'Join', motion: '3D motion',
    sealed: 'Sealed on the device', relayTitle: 'The city becomes', relayEm: 'a route.',
    relayFact: 'Only participating nodes. No random phones.', opaque: 'Content stays closed to the relay',
    receiverTitle: 'Many nodes.', receiverEm: 'One recipient.', security: 'Verify key fingerprints. Encryption does not protect a compromised device.',
    delivered: 'The recipient decrypts it', connectTitle: 'The next node.', connectEm: 'That is you.',
    join: 'Grow the network together', disclaimer: 'A demonstration city, not a user map. Range depends on real nodes or an allowed internet fallback.',
    follow: 'Continue the route', static: 'Static city. Every section is available.'
  },
  ru: {
    sender: 'Отправитель', relay: 'Ретранслятор', receiver: 'Получатель', connect: 'Подключиться', motion: '3D-анимация',
    sealed: 'Зашифровано на устройстве', relayTitle: 'Город становится', relayEm: 'маршрутом.',
    relayFact: 'Только участвующие узлы. Не случайные телефоны.', opaque: 'Содержимое закрыто для узла',
    receiverTitle: 'Много узлов.', receiverEm: 'Один адресат.', security: 'Сверяйте отпечатки ключей. Шифрование не защищает взломанное устройство.',
    delivered: 'Расшифровывает получатель', connectTitle: 'Следующий узел.', connectEm: 'Это вы.',
    join: 'Расширяйте сеть вместе', disclaimer: 'Демонстрационный город, не карта пользователей. Дальность зависит от реальных узлов или разрешённого интернет-резерва.',
    follow: 'Дальше по маршруту', static: 'Статичный город. Все разделы доступны.'
  },
  es: { sender: 'Emisor', relay: 'Retransmisor', receiver: 'Destinatario', connect: 'Unirse', motion: 'Animación 3D', sealed: 'Cifrado en el dispositivo', relayTitle: 'La ciudad se convierte en', relayEm: 'una ruta.', relayFact: 'Solo nodos participantes. No teléfonos aleatorios.', opaque: 'El contenido permanece cerrado al retransmisor', receiverTitle: 'Muchos nodos.', receiverEm: 'Un destinatario.', security: 'Verifica las huellas de las claves. El cifrado no protege un dispositivo comprometido.', delivered: 'El destinatario descifra el mensaje', connectTitle: 'El siguiente nodo.', connectEm: 'Eres tú.', join: 'Haz crecer la red', disclaimer: 'Ciudad de demostración, no mapa de usuarios. El alcance depende de nodos reales o de un respaldo de internet permitido.', follow: 'Seguir la ruta', static: 'Ciudad estática. Todas las secciones están disponibles.' },
  de: { sender: 'Absender', relay: 'Relais', receiver: 'Empfänger', connect: 'Beitreten', motion: '3D-Bewegung', sealed: 'Auf dem Gerät verschlüsselt', relayTitle: 'Die Stadt wird', relayEm: 'zur Route.', relayFact: 'Nur teilnehmende Knoten. Keine zufälligen Telefone.', opaque: 'Der Inhalt bleibt für das Relais geschlossen', receiverTitle: 'Viele Knoten.', receiverEm: 'Ein Empfänger.', security: 'Schlüssel-Fingerabdrücke prüfen. Verschlüsselung schützt kein kompromittiertes Gerät.', delivered: 'Der Empfänger entschlüsselt', connectTitle: 'Der nächste Knoten.', connectEm: 'Das bist du.', join: 'Gemeinsam das Netz erweitern', disclaimer: 'Demostadt, keine Nutzerkarte. Die Reichweite hängt von echten Knoten oder einem erlaubten Internet-Backup ab.', follow: 'Route fortsetzen', static: 'Statische Stadt. Alle Bereiche sind verfügbar.' },
  fr: { sender: 'Expéditeur', relay: 'Relais', receiver: 'Destinataire', connect: 'Rejoindre', motion: 'Animation 3D', sealed: 'Chiffré sur l’appareil', relayTitle: 'La ville devient', relayEm: 'un itinéraire.', relayFact: 'Seulement les noeuds participants. Aucun téléphone aléatoire.', opaque: 'Le contenu reste fermé au relais', receiverTitle: 'Plusieurs noeuds.', receiverEm: 'Un destinataire.', security: 'Vérifiez les empreintes des clés. Le chiffrement ne protège pas un appareil compromis.', delivered: 'Le destinataire déchiffre', connectTitle: 'Le prochain noeud.', connectEm: "C'est vous.", join: 'Développez le réseau ensemble', disclaimer: 'Ville de démonstration, pas une carte des utilisateurs. La portée dépend de noeuds réels ou d’un relais internet autorisé.', follow: 'Continuer la route', static: 'Ville statique. Toutes les sections sont disponibles.' },
  pt: { sender: 'Remetente', relay: 'Retransmissor', receiver: 'Destinatário', connect: 'Entrar', motion: 'Animação 3D', sealed: 'Cifrado no dispositivo', relayTitle: 'A cidade vira', relayEm: 'uma rota.', relayFact: 'Somente nós participantes. Nenhum telefone aleatório.', opaque: 'O conteúdo permanece fechado para o retransmissor', receiverTitle: 'Muitos nós.', receiverEm: 'Um destinatário.', security: 'Verifique as impressões digitais das chaves. A criptografia não protege um dispositivo comprometido.', delivered: 'O destinatário decifra', connectTitle: 'O próximo nó.', connectEm: 'É você.', join: 'Expanda a rede junto', disclaimer: 'Cidade demonstrativa, não mapa de usuários. O alcance depende de nós reais ou de um fallback de internet permitido.', follow: 'Continuar a rota', static: 'Cidade estática. Todas as seções estão disponíveis.' },
  it: { sender: 'Mittente', relay: 'Ripetitore', receiver: 'Destinatario', connect: 'Partecipa', motion: 'Animazione 3D', sealed: 'Cifrato sul dispositivo', relayTitle: 'La città diventa', relayEm: 'un percorso.', relayFact: 'Solo nodi partecipanti. Nessun telefono casuale.', opaque: 'Il contenuto resta chiuso al ripetitore', receiverTitle: 'Molti nodi.', receiverEm: 'Un destinatario.', security: 'Verifica le impronte delle chiavi. La cifratura non protegge un dispositivo compromesso.', delivered: 'Il destinatario decifra', connectTitle: 'Il prossimo nodo.', connectEm: 'Sei tu.', join: 'Fai crescere la rete insieme', disclaimer: 'Città dimostrativa, non mappa degli utenti. La portata dipende da nodi reali o da un fallback internet autorizzato.', follow: 'Continua il percorso', static: 'Città statica. Tutte le sezioni sono disponibili.' },
  tr: { sender: 'Gönderen', relay: 'Aktarıcı', receiver: 'Alıcı', connect: 'Katıl', motion: '3D animasyon', sealed: 'Cihazda şifrelendi', relayTitle: 'Şehir', relayEm: 'bir rotaya dönüşür.', relayFact: 'Yalnızca katılan düğümler. Rastgele telefonlar yok.', opaque: 'İçerik aktarıcıya kapalı kalır', receiverTitle: 'Çok düğüm.', receiverEm: 'Tek alıcı.', security: 'Anahtar parmak izlerini doğrula. Şifreleme ele geçirilmiş cihazı korumaz.', delivered: 'Alıcı şifreyi çözer', connectTitle: 'Sıradaki düğüm.', connectEm: 'Sensin.', join: 'Ağı birlikte büyüt', disclaimer: 'Gösterim şehri, kullanıcı haritası değil. Menzil gerçek düğümlere veya izin verilen internet yedeğine bağlıdır.', follow: 'Rotaya devam et', static: 'Sabit şehir. Tüm bölümler kullanılabilir.' },
  zh: { sender: '发送方', relay: '中继节点', receiver: '接收方', connect: '加入', motion: '3D 动画', sealed: '在设备上加密', relayTitle: '城市成为', relayEm: '一条路径。', relayFact: '只有参与节点，不使用随机手机。', opaque: '中继节点无法查看内容', receiverTitle: '多个节点。', receiverEm: '一个收件人。', security: '核验密钥指纹。加密无法保护已被入侵的设备。', delivered: '接收方解密', connectTitle: '下一个节点。', connectEm: '就是你。', join: '一起扩展网络', disclaimer: '这是演示城市，不是用户地图。距离取决于真实节点或获准的互联网备用路线。', follow: '继续路线', static: '静态城市。所有章节都可访问。' },
  ja: { sender: '送信者', relay: '中継ノード', receiver: '受信者', connect: '参加する', motion: '3D アニメーション', sealed: '端末で暗号化', relayTitle: '街が', relayEm: '経路になる。', relayFact: '参加ノードだけ。知らない端末は使いません。', opaque: '中継ノードは内容を見られません', receiverTitle: '複数のノード。', receiverEm: '一人の宛先。', security: '鍵の指紋を確認してください。暗号化は侵害された端末を守りません。', delivered: '受信者が復号します', connectTitle: '次のノード。', connectEm: 'あなたです。', join: '一緒にネットワークを広げる', disclaimer: 'デモ用の街で、ユーザーマップではありません。距離は実際のノードまたは許可されたインターネット予備経路によって変わります。', follow: '経路を続ける', static: '静的な街。すべての章を利用できます。' },
  ko: { sender: '보내는 사람', relay: '중계 노드', receiver: '받는 사람', connect: '참여', motion: '3D 애니메이션', sealed: '기기에서 암호화', relayTitle: '도시가', relayEm: '경로가 됩니다.', relayFact: '참여 노드만 사용합니다. 무작위 휴대폰은 없습니다.', opaque: '중계 노드는 내용을 볼 수 없습니다', receiverTitle: '여러 노드.', receiverEm: '한 명의 수신자.', security: '키 지문을 확인하세요. 암호화는 침해된 기기를 보호하지 않습니다.', delivered: '수신자가 복호화합니다', connectTitle: '다음 노드.', connectEm: '바로 당신입니다.', join: '함께 네트워크를 키우세요', disclaimer: '시연용 도시이며 사용자 지도는 아닙니다. 거리는 실제 노드 또는 허용된 인터넷 백업 경로에 따라 달라집니다.', follow: '경로 계속', static: '정적 도시입니다. 모든 섹션을 이용할 수 있습니다.' },
  ar: { sender: 'المرسل', relay: 'العقدة المرحِّلة', receiver: 'المستلم', connect: 'انضمام', motion: 'حركة ثلاثية الأبعاد', sealed: 'مشفّر على الجهاز', relayTitle: 'تتحول المدينة إلى', relayEm: 'مسار.', relayFact: 'العقد المشاركة فقط، ولا نستخدم هواتف عشوائية.', opaque: 'يبقى المحتوى مغلقاً أمام المرحّل', receiverTitle: 'عقد كثيرة.', receiverEm: 'مستلم واحد.', security: 'تحقق من بصمات المفاتيح. التشفير لا يحمي جهازاً مخترقاً.', delivered: 'يفك المستلم التشفير', connectTitle: 'العقدة التالية.', connectEm: 'إنها أنت.', join: 'وسّع الشبكة معنا', disclaimer: 'مدينة تجريبية وليست خريطة للمستخدمين. يعتمد المدى على العقد الفعلية أو مسار الإنترنت الاحتياطي المسموح.', follow: 'تابع المسار', static: 'مدينة ثابتة. كل الأقسام متاحة.' },
  hi: { sender: 'भेजने वाला', relay: 'रिले नोड', receiver: 'प्राप्तकर्ता', connect: 'जुड़ें', motion: '3D एनीमेशन', sealed: 'डिवाइस पर एन्क्रिप्टेड', relayTitle: 'शहर बन जाता है', relayEm: 'एक रास्ता।', relayFact: 'सिर्फ भाग लेने वाले नोड। कोई अनजान फोन नहीं।', opaque: 'रिले को सामग्री दिखाई नहीं देती', receiverTitle: 'कई नोड।', receiverEm: 'एक प्राप्तकर्ता।', security: 'कुंजी फिंगरप्रिंट जांचें। एन्क्रिप्शन समझौता किए गए डिवाइस की रक्षा नहीं करता।', delivered: 'प्राप्तकर्ता डिक्रिप्ट करता है', connectTitle: 'अगला नोड।', connectEm: 'वह आप हैं।', join: 'साथ मिलकर नेटवर्क बढ़ाएं', disclaimer: 'डेमो शहर है, उपयोगकर्ताओं का नक्शा नहीं। दूरी वास्तविक नोड या अनुमत इंटरनेट बैकअप पर निर्भर करती है।', follow: 'रास्ते पर आगे', static: 'स्थिर शहर। सभी अनुभाग उपलब्ध हैं।' }
};

const liveCopy = {
  en: 'Live 3D city. Scroll to follow the route.',
  ru: 'Живой 3D-город. Листайте, чтобы следить за маршрутом.',
  es: 'Ciudad 3D viva. Desplázate para seguir la ruta.',
  de: 'Lebendige 3D-Stadt. Scrolle, um der Route zu folgen.',
  fr: 'Ville 3D vivante. Faites défiler pour suivre la route.',
  pt: 'Cidade 3D viva. Role para seguir a rota.',
  it: 'Città 3D viva. Scorri per seguire il percorso.',
  tr: 'Canlı 3D şehir. Rotayı izlemek için kaydırın.',
  zh: '动态 3D 城市。滚动查看路线。',
  ja: '動く3D都市。スクロールして経路を追跡。',
  ko: '움직이는 3D 도시. 스크롤하여 경로를 따라가세요.',
  ar: 'مدينة ثلاثية الأبعاد حية. مرر لمتابعة المسار.',
  hi: 'जीवंत 3D शहर। रास्ते को देखने के लिए स्क्रॉल करें.'
};

function language() {
  const lang = (document.documentElement.lang || navigator.language || 'en').slice(0, 2).toLowerCase();
  return worldCopy[lang] ? lang : 'en';
}

function applyCopy() {
  const copy = worldCopy[language()];
  document.querySelectorAll('[data-world-text]').forEach((element) => {
    const key = element.getAttribute('data-world-text');
    if (copy[key]) element.textContent = copy[key];
  });
}

function setStatic(message = worldCopy[language()].static) {
  if (root) root.dataset.worldState = 'static';
  if (status) status.textContent = message;
  if (toggle) toggle.hidden = true;
}

function boot() {
  applyCopy();
  new MutationObserver(applyCopy).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] });
  if (!root || !canvas || !window.WebGLRenderingContext) return setStatic();
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, powerPreference: 'high-performance' });
  } catch (_) {
    return setStatic();
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.5;

  const scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x070a16, 0.045);
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
  camera.position.set(5.6, 3.4, 8.2);
  const city = new THREE.Group();
  scene.add(city);

  scene.add(new THREE.HemisphereLight(0x9fe9ff, 0x08091d, 1.25));
  const cyanLight = new THREE.PointLight(0x52e7ff, 5, 12, 2);
  cyanLight.position.set(-2.5, 2.2, 2); scene.add(cyanLight);
  const pinkLight = new THREE.PointLight(0xf35bd8, 4, 10, 2);
  pinkLight.position.set(3.5, 1.4, -2); scene.add(pinkLight);

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(28, 28),
    new THREE.MeshStandardMaterial({ color: 0x091427, roughness: 0.78, metalness: 0.18 })
  );
  ground.rotation.x = -Math.PI / 2; ground.position.y = -0.06; city.add(ground);
  const grid = new THREE.GridHelper(26, 26, 0x1c5570, 0x14314a);
  grid.position.y = -0.03; grid.material.transparent = true; grid.material.opacity = 0.48; city.add(grid);

  const rng = (seed) => { const x = Math.sin(seed * 12.9898) * 43758.5453; return x - Math.floor(x); };
  const buildings = new THREE.Group(); city.add(buildings);
  for (let i = 0; i < 50; i += 1) {
    const x = (rng(i + 2) - 0.5) * 15;
    const z = (rng(i + 70) - 0.5) * 13;
    if (Math.abs(x) < 2 && Math.abs(z) < 2) continue;
    const width = 0.25 + rng(i + 140) * 0.65;
    const height = 0.28 + rng(i + 210) * 2.25;
    const material = new THREE.MeshStandardMaterial({
      color: i % 5 === 0 ? 0x151d3a : 0x0d1930,
      roughness: 0.65, metalness: 0.2,
      emissive: i % 7 === 0 ? 0x102d44 : 0x060b1b,
      emissiveIntensity: 0.85
    });
    const building = new THREE.Mesh(new THREE.BoxGeometry(width, height, width), material);
    building.position.set(x, height / 2, z); buildings.add(building);
    const outline = new THREE.LineSegments(new THREE.EdgesGeometry(building.geometry),
      new THREE.LineBasicMaterial({ color: i % 3 ? 0x329cab : 0xc568f0, transparent: true, opacity: .5 }));
    building.add(outline);
    const windowMaterial = new THREE.MeshBasicMaterial({ color: i % 3 ? 0x75e7ee : 0xefb1ff });
    for (let floor = .16; floor < height - .06; floor += .22) {
      const strip = new THREE.Mesh(new THREE.BoxGeometry(width * .72, .025, width + .004), windowMaterial);
      strip.position.y = floor - height / 2; building.add(strip);
    }
  }

  const rings = new THREE.Group(); city.add(rings);
  [2.1, 2.5, 3.0].forEach((radius, i) => {
    const ring = new THREE.Mesh(new THREE.TorusGeometry(radius, .018, 8, 160),
      new THREE.MeshBasicMaterial({ color: i === 1 ? 0xd16aff : 0x57f4ea, transparent: true, opacity: .75 }));
    ring.rotation.x = Math.PI / 2 + i * .16; ring.position.y = .12 + i * .13; rings.add(ring);
  });

  const route = [
    new THREE.Vector3(-3.7, 0.46, 2.4), new THREE.Vector3(-1.55, 0.82, 0.7),
    new THREE.Vector3(0.15, 1.12, -0.15), new THREE.Vector3(2.1, 0.75, -1.25),
    new THREE.Vector3(4.0, 0.52, -2.2)
  ];
  const colors = [0x52e7ff, 0xf35bd8, 0xb8ffdd, 0x7b86ff, 0xffaa58];
  const links = new THREE.Group(); city.add(links);
  route.slice(0, -1).forEach((from, i) => {
    const points = [];
    for (let j = 0; j <= 24; j += 1) {
      const p = from.clone().lerp(route[i + 1], j / 24);
      p.y += Math.sin(Math.PI * j / 24) * 0.45;
      points.push(p);
    }
    links.add(new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineBasicMaterial({ color: colors[i], transparent: true, opacity: 0.78 })
    ));
  });

  const nodes = new THREE.Group(); city.add(nodes);
  route.forEach((point, i) => {
    const halo = new THREE.Mesh(
      new THREE.SphereGeometry(0.21 + (i === 2 ? 0.08 : 0), 20, 20),
      new THREE.MeshBasicMaterial({ color: colors[i], transparent: true, opacity: 0.18 })
    );
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(i === 2 ? 0.09 : 0.055, 18, 18),
      new THREE.MeshStandardMaterial({ color: colors[i], emissive: colors[i], emissiveIntensity: 2.4, roughness: 0.3 })
    );
    const group = new THREE.Group(); group.position.copy(point); group.add(halo, core); nodes.add(group);
  });
  const packet = new THREE.Mesh(
    new THREE.OctahedronGeometry(0.11, 1),
    new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0x52e7ff, emissiveIntensity: 3, roughness: 0.2 })
  );
  city.add(packet);

  const stars = new THREE.Points(
    new THREE.BufferGeometry(),
    new THREE.PointsMaterial({ color: 0xa9eaff, size: 0.035, transparent: true, opacity: 0.75, sizeAttenuation: true })
  );
  const starPositions = new Float32Array(420 * 3);
  for (let i = 0; i < 420; i += 1) { starPositions[i * 3] = (rng(i + 900) - .5) * 26; starPositions[i * 3 + 1] = 1.5 + rng(i + 1100) * 9; starPositions[i * 3 + 2] = (rng(i + 1300) - .5) * 20; }
  stars.geometry.setAttribute('position', new THREE.BufferAttribute(starPositions, 3)); scene.add(stars);

  let active = !reduced.matches;
  let scrollProgress = 0;
  let frame = 0;
  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, rect.width || window.innerWidth);
    const height = Math.max(1, rect.height || window.innerHeight);
    camera.aspect = width / height; camera.updateProjectionMatrix(); renderer.setSize(width, height, false);
  };
  const updateProgress = () => {
    const rect = root.getBoundingClientRect();
    scrollProgress = Math.min(1, Math.max(0, -rect.top / Math.max(1, rect.height - window.innerHeight)));
    root.style.setProperty('--world-progress', scrollProgress.toFixed(4));
    const chapters = [...root.querySelectorAll('.world-chapter')];
    let current = chapters[0];
    chapters.forEach((chapter) => { const r = chapter.getBoundingClientRect(); if (r.top <= window.innerHeight * .46) current = chapter; });
    root.querySelectorAll('.world-chapters a').forEach((link) => link.toggleAttribute('aria-current', link.getAttribute('href') === `#${current?.id}`));
  };
  const render = (time = 0) => {
    updateProgress();
    const t = time * 0.00016;
    const segment = Math.min(route.length - 2.001, scrollProgress * (route.length - 1));
    const segmentIndex = Math.floor(segment);
    const local = segment - segmentIndex;
    const from = route[segmentIndex];
    const to = route[segmentIndex + 1];
    packet.position.copy(from).lerp(to, local);
    packet.position.y += Math.sin(Math.PI * local) * 0.45;
    packet.rotation.x = t * 22; packet.rotation.y = t * 29;
    nodes.children.forEach((node, i) => { const pulse = 1 + Math.sin(t * 7 + i * 1.4) * 0.11; node.scale.setScalar(pulse); });
    stars.rotation.y = t * 0.16;
    rings.rotation.y = t * .35;
    camera.position.x += ((5.6 + Math.sin(t * 2.2) * 0.35 + (scrollProgress - .5) * 1.1) - camera.position.x) * 0.035;
    camera.position.y += ((3.4 + Math.cos(t * 1.4) * 0.18 + scrollProgress * .7) - camera.position.y) * 0.035;
    camera.lookAt(0, .75, 0);
    renderer.render(scene, camera);
    if (!reduced.matches && active) frame = requestAnimationFrame(render);
  };
  const onScroll = () => { if (reduced.matches) render(0); };
  window.addEventListener('resize', resize, { passive: true });
  window.addEventListener('scroll', onScroll, { passive: true });
  const setMotionState = () => {
    const copy = worldCopy[language()];
    toggle.setAttribute('aria-pressed', String(active));
    status.textContent = active ? (liveCopy[language()] || copy.static) : copy.static;
  };
  resize(); root.dataset.worldState = 'ready'; toggle.hidden = reduced.matches;
  toggle.setAttribute('aria-label', worldCopy[language()].motion);
  toggle.addEventListener('click', () => { active = !active; setMotionState(); if (active && !reduced.matches) frame = requestAnimationFrame(render); if (!active && frame) cancelAnimationFrame(frame); });
  setMotionState();
  if (reduced.matches) render(0); else frame = requestAnimationFrame(render);
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
else boot();
