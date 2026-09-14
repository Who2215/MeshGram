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
  const compactDisplay = window.matchMedia('(max-width: 800px), (pointer: coarse)');
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, compactDisplay.matches ? 1.5 : 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.55;

  const scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x050816, 0.038);
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
  camera.position.set(5.8, 3.5, 9.4);
  const city = new THREE.Group();
  scene.add(city);

  const rng = (seed) => { const x = Math.sin(seed * 12.9898) * 43758.5453; return x - Math.floor(x); };
  scene.add(new THREE.HemisphereLight(0x8feeff, 0x050611, 1.35));
  const cyanLight = new THREE.PointLight(0x52e7ff, 9, 15, 2);
  cyanLight.position.set(-2.8, 3.4, 3.4); scene.add(cyanLight);
  const pinkLight = new THREE.PointLight(0xf35bd8, 7, 14, 2);
  pinkLight.position.set(3.8, 2.6, -2.8); scene.add(pinkLight);
  const limeLight = new THREE.PointLight(0xb8ffdd, 4.5, 10, 2);
  limeLight.position.set(2.2, 1.5, 4.2); scene.add(limeLight);

  const ground = new THREE.Mesh(
    new THREE.CircleGeometry(12, 96),
    new THREE.MeshBasicMaterial({ color: 0x071126, transparent: true, opacity: .76, side: THREE.DoubleSide })
  );
  ground.rotation.x = -Math.PI / 2; ground.position.y = -0.2; city.add(ground);
  const grid = new THREE.PolarGridHelper(12, 16, 12, 128, 0x276078, 0x132a46);
  grid.position.y = -0.17;
  const gridMaterials = Array.isArray(grid.material) ? grid.material : [grid.material];
  gridMaterials.forEach((material) => {
    material.transparent = true;
    material.opacity = .34;
  });
  city.add(grid);

  const core = new THREE.Group();
  core.position.set(1.05, 1.8, -0.65);
  city.add(core);
  const coreGlow = new THREE.Mesh(
    new THREE.SphereGeometry(.78, 40, 40),
    new THREE.MeshStandardMaterial({ color: 0x0b2940, emissive: 0x1b9fba, emissiveIntensity: 1.65, roughness: .2, metalness: .35, transparent: true, opacity: .84 })
  );
  const coreShell = new THREE.Mesh(
    new THREE.IcosahedronGeometry(1.24, 2),
    new THREE.MeshBasicMaterial({ color: 0x72f5ef, wireframe: true, transparent: true, opacity: .25, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  const coreNucleus = new THREE.Mesh(
    new THREE.IcosahedronGeometry(.24, 1),
    new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: .95, blending: THREE.AdditiveBlending })
  );
  core.add(coreGlow, coreShell, coreNucleus);

  const rings = new THREE.Group();
  core.add(rings);
  [1.45, 1.8, 2.18].forEach((radius, i) => {
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(radius, .018 + i * .004, 8, 180),
      new THREE.MeshBasicMaterial({ color: i === 1 ? 0xf35bd8 : (i === 2 ? 0x7b86ff : 0x52e7ff), transparent: true, opacity: .58 - i * .1, blending: THREE.AdditiveBlending, depthWrite: false })
    );
    ring.rotation.set(Math.PI / 2 + i * .38, i * .27, i * .72);
    rings.add(ring);
  });

  const constellation = new THREE.Group();
  city.add(constellation);
  const ambientPositions = [];
  const ambientColors = [];
  const ambientVectors = [];
  const palette = [new THREE.Color(0x52e7ff), new THREE.Color(0xf35bd8), new THREE.Color(0x7b86ff), new THREE.Color(0xb8ffdd)];
  for (let i = 0; i < 82; i += 1) {
    const angle = rng(i + 15) * Math.PI * 2;
    const radius = 2.6 + rng(i + 41) * 7.8;
    const point = new THREE.Vector3(Math.cos(angle) * radius, .12 + rng(i + 88) * 3.6, Math.sin(angle) * radius * .72);
    ambientVectors.push(point);
    ambientPositions.push(point.x, point.y, point.z);
    const color = palette[i % palette.length];
    ambientColors.push(color.r, color.g, color.b);
  }
  const ambientGeometry = new THREE.BufferGeometry();
  ambientGeometry.setAttribute('position', new THREE.Float32BufferAttribute(ambientPositions, 3));
  ambientGeometry.setAttribute('color', new THREE.Float32BufferAttribute(ambientColors, 3));
  const ambientNodes = new THREE.Points(
    ambientGeometry,
    new THREE.PointsMaterial({ size: .075, vertexColors: true, transparent: true, opacity: .86, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  constellation.add(ambientNodes);

  const meshSegments = [];
  ambientVectors.forEach((point, i) => {
    const partner = ambientVectors[(i * 13 + 17) % ambientVectors.length];
    if (point.distanceTo(partner) < 4.8) meshSegments.push(point.x, point.y, point.z, partner.x, partner.y, partner.z);
  });
  const meshGeometry = new THREE.BufferGeometry();
  meshGeometry.setAttribute('position', new THREE.Float32BufferAttribute(meshSegments, 3));
  const meshLines = new THREE.LineSegments(
    meshGeometry,
    new THREE.LineBasicMaterial({ color: 0x4ad9eb, transparent: true, opacity: .13, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  constellation.add(meshLines);

  const route = [
    new THREE.Vector3(-4.5, .55, 2.7), new THREE.Vector3(-2.1, 1.18, .85),
    new THREE.Vector3(.55, 1.65, -.35), new THREE.Vector3(2.65, 1.05, -1.4),
    new THREE.Vector3(4.65, .72, -2.45)
  ];
  const colors = [0x52e7ff, 0xf35bd8, 0xb8ffdd, 0x7b86ff, 0xffaa58];
  const routeCurve = new THREE.CatmullRomCurve3(route, false, 'catmullrom', .42);
  const links = new THREE.Group(); city.add(links);
  const routeGlow = new THREE.Mesh(
    new THREE.TubeGeometry(routeCurve, 180, .115, 8, false),
    new THREE.MeshBasicMaterial({ color: 0x52e7ff, transparent: true, opacity: .08, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  const routeBeam = new THREE.Mesh(
    new THREE.TubeGeometry(routeCurve, 180, .024, 7, false),
    new THREE.MeshBasicMaterial({ color: 0xb9fbff, transparent: true, opacity: .72, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  links.add(routeGlow, routeBeam);
  [-.2, .2].forEach((offset, i) => {
    const echoPoints = route.map((point, index) => point.clone().add(new THREE.Vector3(0, offset * (index % 2 ? -1 : 1), offset * 2.2)));
    const echoCurve = new THREE.CatmullRomCurve3(echoPoints, false, 'catmullrom', .5);
    links.add(new THREE.Mesh(
      new THREE.TubeGeometry(echoCurve, 140, .012, 6, false),
      new THREE.MeshBasicMaterial({ color: i ? 0xf35bd8 : 0x7b86ff, transparent: true, opacity: .28, blending: THREE.AdditiveBlending, depthWrite: false })
    ));
  });

  const nodes = new THREE.Group(); city.add(nodes);
  route.forEach((point, i) => {
    const halo = new THREE.Mesh(
      new THREE.SphereGeometry(.29 + (i === 2 ? .1 : 0), 24, 24),
      new THREE.MeshBasicMaterial({ color: colors[i], transparent: true, opacity: .14, blending: THREE.AdditiveBlending, depthWrite: false })
    );
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(i === 2 ? .12 : .08, 20, 20),
      new THREE.MeshStandardMaterial({ color: colors[i], emissive: colors[i], emissiveIntensity: 3, roughness: .18 })
    );
    const orbit = new THREE.Mesh(
      new THREE.TorusGeometry(.33 + i * .018, .014, 7, 64),
      new THREE.MeshBasicMaterial({ color: colors[i], transparent: true, opacity: .68, blending: THREE.AdditiveBlending, depthWrite: false })
    );
    orbit.rotation.x = Math.PI / 2;
    const beaconGeometry = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, -point.y + .03, 0), new THREE.Vector3(0, 0, 0)]);
    const beacon = new THREE.Line(beaconGeometry, new THREE.LineBasicMaterial({ color: colors[i], transparent: true, opacity: .24 }));
    const group = new THREE.Group();
    group.position.copy(point);
    group.userData.phase = i * 1.3;
    group.add(halo, core, orbit, beacon);
    nodes.add(group);
  });
  const packet = new THREE.Group();
  const packetCore = new THREE.Mesh(
    new THREE.OctahedronGeometry(.13, 1),
    new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0x52e7ff, emissiveIntensity: 4, roughness: .12 })
  );
  const packetOrbit = new THREE.Mesh(
    new THREE.TorusGeometry(.23, .018, 7, 48),
    new THREE.MeshBasicMaterial({ color: 0xf35bd8, transparent: true, opacity: .9, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  packet.add(packetCore, packetOrbit);
  city.add(packet);

  const trail = new THREE.Group();
  city.add(trail);
  for (let i = 0; i < 9; i += 1) {
    trail.add(new THREE.Mesh(
      new THREE.SphereGeometry(.052 - i * .0035, 10, 10),
      new THREE.MeshBasicMaterial({ color: i % 2 ? 0xf35bd8 : 0x52e7ff, transparent: true, opacity: .54 - i * .045, blending: THREE.AdditiveBlending, depthWrite: false })
    ));
  }

  const stars = new THREE.Points(
    new THREE.BufferGeometry(),
    new THREE.PointsMaterial({ color: 0xbdeeff, size: .04, transparent: true, opacity: .72, sizeAttenuation: true, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  const starPositions = new Float32Array(520 * 3);
  for (let i = 0; i < 520; i += 1) { starPositions[i * 3] = (rng(i + 900) - .5) * 30; starPositions[i * 3 + 1] = 1.2 + rng(i + 1100) * 10; starPositions[i * 3 + 2] = (rng(i + 1300) - .5) * 24; }
  stars.geometry.setAttribute('position', new THREE.BufferAttribute(starPositions, 3)); scene.add(stars);

  let active = !reduced.matches;
  let pageVisible = !document.hidden;
  let worldVisible = true;
  let scrollProgress = 0;
  let frame = 0;
  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, rect.width || window.innerWidth);
    const height = Math.max(1, rect.height || window.innerHeight);
    const compact = compactDisplay.matches;
    camera.fov = compact ? 49 : 42;
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, compact ? 1.5 : 2));
    renderer.setSize(width, height, false);
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
  const scheduleRender = () => {
    if (!frame && active && pageVisible && worldVisible && !reduced.matches) frame = requestAnimationFrame(render);
  };
  const render = (time = 0) => {
    frame = 0;
    updateProgress();
    const t = time * 0.00016;
    const compact = compactDisplay.matches;
    const routeProgress = THREE.MathUtils.smoothstep(scrollProgress, 0, 1);
    routeCurve.getPointAt(routeProgress, packet.position);
    packetCore.rotation.x = t * 18;
    packetCore.rotation.y = t * 23;
    packetOrbit.rotation.x = Math.PI / 2 + Math.sin(t * 3) * .35;
    packetOrbit.rotation.y = t * 5;
    trail.children.forEach((dot, i) => {
      const trailProgress = Math.max(0, routeProgress - (i + 1) * .017);
      routeCurve.getPointAt(trailProgress, dot.position);
      dot.visible = routeProgress > i * .012;
    });
    nodes.children.forEach((node, i) => {
      const pulse = 1 + Math.sin(t * 6 + node.userData.phase) * .13;
      node.children[0].scale.setScalar(pulse);
      node.children[2].rotation.z = t * (i % 2 ? -1.5 : 1.5) + i;
    });
    const corePulse = 1 + Math.sin(t * 4.2) * .045;
    coreGlow.scale.setScalar(corePulse);
    coreNucleus.scale.setScalar(1 + Math.sin(t * 7.5) * .14);
    coreShell.rotation.x = t * .42;
    coreShell.rotation.y = -t * .55;
    rings.rotation.x = Math.sin(t * .7) * .12;
    rings.rotation.y = t * .2;
    constellation.rotation.y = -t * .055;
    meshLines.material.opacity = .11 + Math.sin(t * 1.8) * .035;
    routeGlow.material.opacity = .075 + Math.sin(t * 3.4) * .025;
    stars.rotation.y = t * .035;
    stars.rotation.x = Math.sin(t * .18) * .025;
    city.position.x += ((compact ? -.25 : 1.1) - city.position.x) * .04;
    city.rotation.y += ((scrollProgress - .5) * .14 - city.rotation.y) * .025;
    const targetX = compact ? .15 + Math.sin(t * 1.1) * .08 : 5.8 + Math.sin(t * 1.4) * .28 + (scrollProgress - .5) * .7;
    const targetY = compact ? 3.15 + scrollProgress * .35 : 3.5 + Math.cos(t * .9) * .14 + scrollProgress * .55;
    const targetZ = compact ? 11.4 - Math.sin(scrollProgress * Math.PI) * .45 : 9.4 - Math.sin(scrollProgress * Math.PI) * .65;
    camera.position.x += (targetX - camera.position.x) * .045;
    camera.position.y += (targetY - camera.position.y) * .045;
    camera.position.z += (targetZ - camera.position.z) * .045;
    camera.lookAt(compact ? .2 : .8, 1.15, -.55);
    renderer.render(scene, camera);
    scheduleRender();
  };
  const onScroll = () => { if (reduced.matches) render(0); };
  window.addEventListener('resize', resize, { passive: true });
  if (window.visualViewport) window.visualViewport.addEventListener('resize', resize, { passive: true });
  window.addEventListener('scroll', onScroll, { passive: true });
  if ('ResizeObserver' in window) {
    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(canvas);
  }
  if ('IntersectionObserver' in window) {
    const visibilityObserver = new IntersectionObserver(([entry]) => {
      worldVisible = entry.isIntersecting;
      root.dataset.worldRunning = String(active && pageVisible && worldVisible && !reduced.matches);
      if (!worldVisible && frame) {
        cancelAnimationFrame(frame);
        frame = 0;
      } else {
        scheduleRender();
      }
    }, { rootMargin: '160px 0px' });
    visibilityObserver.observe(root);
  }
  document.addEventListener('visibilitychange', () => {
    pageVisible = !document.hidden;
    root.dataset.worldRunning = String(active && pageVisible && worldVisible && !reduced.matches);
    if (!pageVisible && frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    } else {
      scheduleRender();
    }
  });
  const setMotionState = () => {
    const copy = worldCopy[language()];
    toggle.setAttribute('aria-pressed', String(active));
    status.textContent = active ? (liveCopy[language()] || copy.static) : copy.static;
    root.dataset.worldRunning = String(active && pageVisible && worldVisible && !reduced.matches);
  };
  resize(); root.dataset.worldState = 'ready'; toggle.hidden = reduced.matches;
  toggle.setAttribute('aria-label', worldCopy[language()].motion);
  toggle.addEventListener('click', () => {
    active = !active;
    setMotionState();
    if (active) scheduleRender();
    if (!active && frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
  });
  setMotionState();
  if (reduced.matches) render(0); else scheduleRender();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
else boot();
