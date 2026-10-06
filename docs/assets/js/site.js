/* =====================================================================
   N3 Gallery – Produktseite
   Bewegung, Zweisprachigkeit, Kachelwand. Keine Abhängigkeiten,
   keine Anfragen nach außen.
   ===================================================================== */
(() => {
  'use strict';

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  /* ------------------------------------------------------------------
     1. Kachelwand – die Formate, die die App öffnet.
     Angaben stammen aus der App (Formats.kt), nicht aus der Fantasie.
     ------------------------------------------------------------------ */
  const TILES = [
    ['heic', 'Kachel-Raster 8 × 6 · 10 Bit', 'Tiled grid 8 × 6 · 10-bit', 1],
    ['heif', 'HDR mit Gain-Map', 'HDR with gain map', 1],
    ['hif', 'Apple-Kurzform für HEIF', 'Apple shorthand for HEIF', 0],
    ['avif', 'AV1-Decoder in der App', 'AV1 decoder built in', 1],
    ['dng', 'Adobe-RAW, eingebettete Vorschau', 'Adobe RAW, embedded preview', 0],
    ['cr2', 'Canon RAW (ältere Modelle)', 'Canon RAW (older models)', 0],
    ['cr3', 'Canon RAW, HEIF-basiert', 'Canon RAW, HEIF based', 0],
    ['nef', 'Nikon RAW', 'Nikon RAW', 0],
    ['arw', 'Sony RAW', 'Sony RAW', 0],
    ['orf', 'Olympus / OM System', 'Olympus / OM System', 0],
    ['rw2', 'Panasonic RAW', 'Panasonic RAW', 0],
    ['raf', 'Fujifilm RAW', 'Fujifilm RAW', 0],
    ['pef', 'Pentax RAW', 'Pentax RAW', 0],
    ['srw', 'Samsung RAW', 'Samsung RAW', 0],
    ['x3f', 'Sigma Foveon', 'Sigma Foveon', 0],
    ['iiq', 'Phase One', 'Phase One', 0],
    ['jpg', 'Systemweg, schnellster Fall', 'System path, fastest case', 0],
    ['png', 'verlustfrei inkl. Transparenz', 'lossless incl. transparency', 0],
    ['webp', 'Systemweg', 'System path', 0],
    ['gif', 'Animation erkannt', 'animation detected', 0],
    ['bmp', 'Systemweg', 'System path', 0],
    ['tiff', 'Systemweg', 'System path', 0],
    ['svg', 'wird in Zielgröße gezeichnet', 'drawn at target size', 1],
    ['svgz', 'gzip-gepacktes SVG', 'gzip-packed SVG', 0],
    ['mp4', 'Einzelbild über HEVC', 'still frame via HEVC', 0],
    ['mov', 'QuickTime, HEVC/H.264', 'QuickTime, HEVC/H.264', 0],
    ['hevc', '10-Bit-Video möglich', '10-bit video capable', 0],
    ['mkv', 'Container-Format', 'container format', 0],
    ['webm', 'Container-Format', 'container format', 0],
    ['mts', 'AVCHD-Kameras', 'AVCHD cameras', 0]
  ];

  const langOf = () => (document.documentElement.lang === 'en' ? 'en' : 'de');
  const HOVER_OK = () => window.matchMedia('(hover: hover) and (pointer: fine)').matches;
  const DEFAULT_READOUT = () => (document.documentElement.lang === 'en'
    ? 'Container 8 × 6 · 10-bit · HDR · gain map'
    : 'Container 8 × 6 · 10 Bit · HDR · Gain-Map');

  /* Ohne Mauszeiger lautet der Hinweis „antippen“ statt „zeigen“ */
  function setHint() {
    const hint = $('#readoutHint');
    if (!hint || HOVER_OK()) return;
    hint.removeAttribute('data-i18n');
    hint.textContent = langOf() === 'en' ? 'Tap to inspect' : 'Antippen zum Prüfen';
  }

  function buildWall() {
    const host = $('#tiles');
    if (!host) return;
    host.textContent = ''; // Hinweistext für den Fall ohne Skript entfernen
    const frag = document.createDocumentFragment();
    TILES.forEach(([label, factDe, factEn], i) => {
      const tile = document.createElement('div');
      tile.className = 'tile' + (TILES[i][3] ? ' tile--accent' : '');
      // Nähe zur Mitte bestimmt die Reihenfolge beim Zusammensetzen
      const row = Math.floor(i / 6), col = i % 6;
      const dist = Math.abs(row - 2) + Math.abs(col - 2.5);
      tile.style.setProperty('--d', String(Math.round(dist * 1.6)));
      tile.dataset.factDe = factDe;
      tile.dataset.factEn = factEn;
      tile.setAttribute('title', label.toUpperCase() + ' — ' + factDe);
      const span = document.createElement('span');
      span.className = 'tile__label';
      span.textContent = label;
      tile.appendChild(span);
      frag.appendChild(tile);
    });
    host.appendChild(frag);

    // Lichtfläche folgt der Maus, Fakten erscheinen in der Zeile darunter
    const readout = $('#readout');
    host.addEventListener('pointermove', (e) => {
      const tile = e.target.closest('.tile');
      if (!tile) return;
      const r = tile.getBoundingClientRect();
      tile.style.setProperty('--mx', ((e.clientX - r.left) / r.width * 100) + '%');
      tile.style.setProperty('--my', ((e.clientY - r.top) / r.height * 100) + '%');
    });
    const show = (tile) => {
      if (!readout) return;
      const key = langOf() === 'en' ? 'factEn' : 'factDe';
      readout.textContent = tile.textContent.toUpperCase() + ' — ' + tile.dataset[key];
    };
    host.addEventListener('pointerover', (e) => {
      const tile = e.target.closest('.tile');
      if (tile) show(tile);
    });
    host.addEventListener('pointerleave', () => {
      if (readout) readout.textContent = DEFAULT_READOUT();
    });
    // Auf Touch-Geräten zeigt das Antippen den Fakt
    host.addEventListener('click', (e) => {
      const tile = e.target.closest('.tile');
      if (tile) show(tile);
    });

    setHint();
  }

  /* ------------------------------------------------------------------
     2. Formatband (läuft langsam durch, hält bei Berührung an)
     ------------------------------------------------------------------ */
  function buildBand() {
    const track = $('#band');
    if (!track) return;
    const names = ['HEIC', 'HEIF', '10-BIT', 'HDR', 'GAIN-MAP', 'KACHEL-RASTER', 'AVIF',
      'RAW', 'DNG', 'SVG', 'WEBP', 'TIFF', 'HEVC', 'GIF', 'X3F', 'IIQ'];
    const html = names.map(n => `<span><i></i>${n}</span>`).join('');
    // vier gleiche Ketten: -50 % ist damit exakt der halbe Weg, die Schleife
    // läuft ohne Sprung und reicht auch für sehr breite Fenster.
    track.innerHTML = html.repeat(4);
  }

  /* ------------------------------------------------------------------
     3. Erscheinen beim Scrollen, Zahlen, Balken
     ------------------------------------------------------------------ */
  function observeReveal() {
    const items = $$('[data-reveal]');
    if (reduce || !('IntersectionObserver' in window)) {
      items.forEach(el => el.classList.add('is-in'));
      $$('[data-count]').forEach(el => { el.textContent = el.dataset.count; });
      return;
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
    items.forEach(el => io.observe(el));
  }

  function countUp() {
    const nums = $$('[data-count]');
    if (!nums.length) return;
    const ease = t => 1 - Math.pow(1 - t, 4);
    const run = (el) => {
      const target = parseInt(el.dataset.count, 10) || 0;
      if (reduce || target === 0) { el.textContent = String(target); return; }
      const start = performance.now();
      const dur = 1100;
      const step = (now) => {
        const t = Math.min(1, (now - start) / dur);
        el.textContent = String(Math.round(target * ease(t)));
        if (t < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    const io = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        run(entry.target);
        io.unobserve(entry.target);
      });
    }, { threshold: 0.6 });
    nums.forEach(el => io.observe(el));
  }

  /* ------------------------------------------------------------------
     4. Ablauf der Dekoder-Kette: die Stufen werden nacheinander „live“
     ------------------------------------------------------------------ */
  /* Jede Stufe schaltet das Fenster links weiter. Die Stufe selbst wird
     nur „live“ markiert, wenn sie die Mitte des Schirms erreicht. */

  const STAGES = [
    { state: 'dark',     de: ['Android-Systemweg', 'Ergebnis: schwarze Fläche'], en: ['Android system path', 'Result: a black frame'] },
    { state: 'dark',     de: ['Hardware-HEVC', 'verworfen – nur schwarz'],       en: ['Hardware HEVC', 'discarded – black only'] },
    { state: 'assemble', de: ['libheif', 'Kachel für Kachel gesetzt'],           en: ['libheif', 'assembled tile by tile'] },
    { state: 'ready',    de: ['Kamera-Vorschau', 'sofort, volle Qualität'],      en: ['Camera preview', 'instantly, full quality'] },
    { state: 'scan',     de: ['Suche im Container', 'JPEG-Daten gefunden'],      en: ['Container search', 'JPEG data found'] }
  ];
  let stageIndex = -1;

  function buildViewer() {
    const host = $('#viewerTiles');
    const viewer = $('#viewer');
    if (!host || !viewer) return;
    const cols = 6, rows = 4;
    const frag = document.createDocumentFragment();
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const cell = document.createElement('div');
        cell.className = 'shutter';
        cell.style.setProperty('--d', String(c + r)); // Diagonale von links oben
        frag.appendChild(cell);
      }
    }
    host.appendChild(frag);
    viewer.dataset.state = 'dark';
    setStage(0);
  }

  function setStage(i, force) {
    const viewer = $('#viewer');
    if (!viewer) return;
    const next = Math.max(0, Math.min(STAGES.length - 1, i));
    if (next === stageIndex && !force) return;
    stageIndex = next;
    const stage = STAGES[stageIndex];
    const words = langOf() === 'en' ? stage.en : stage.de;
    viewer.dataset.state = stage.state;
    const badge = $('#viewerBadge');
    if (badge) badge.textContent = words[0];
    const note = $('#viewerNote');
    if (note) note.textContent = words[1];
  }

  function observeChain() {
    const steps = $$('[data-step]');
    if (!steps.length) return;
    if (!('IntersectionObserver' in window)) { setStage(STAGES.length - 1); return; }
    const io = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        const i = steps.indexOf(entry.target);
        entry.target.classList.toggle('is-live', entry.isIntersecting);
        if (entry.isIntersecting && i >= 0) setStage(i);
      });
    }, { rootMargin: '-45% 0px -45% 0px', threshold: 0 });
    steps.forEach(el => io.observe(el));
    // Beim Klick springt das Fenster direkt auf diese Stufe
    steps.forEach((el, i) => el.addEventListener('click', () => setStage(i)));
  }

  /* ------------------------------------------------------------------
     5. Kopfbereich: Höhe, Fortschritt, aktive Navigation
     ------------------------------------------------------------------ */
  function scrollChrome() {
    const top = $('#top');
    const bar = $('#progress');
    let raf = 0;
    const update = () => {
      raf = 0;
      const y = window.scrollY;
      if (top) top.classList.toggle('is-stuck', y > 24);
      if (bar) {
        const max = document.documentElement.scrollHeight - window.innerHeight;
        bar.style.transform = 'scaleX(' + (max > 0 ? Math.min(1, y / max) : 0) + ')';
      }
      // aktive Rubrik (Leiste und schmales Ausklappmenü zusammen)
      const links = $$('.nav a[href^="#"], .top__panel a[href^="#"]');
      if (links.length) {
        let current = null;
        links.forEach(a => {
          const el = document.getElementById(a.getAttribute('href').slice(1));
          if (el && el.getBoundingClientRect().top <= 140) current = a;
        });
        links.forEach(a => a.setAttribute('aria-current', String(a === current)));
      }
    };
    window.addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(update); }, { passive: true });
    update();
  }

  /* ------------------------------------------------------------------
     5b. Ausklappmenü für schmale Geräte
     ------------------------------------------------------------------ */
  function mobileMenu() {
    const top = $('#top');
    const btn = $('#menu');
    const panel = $('#navPanel');
    if (!top || !btn || !panel) return;

    let openedAt = 0;
    const setOpen = (open) => {
      top.classList.toggle('is-open', open);
      btn.setAttribute('aria-expanded', String(open));
      btn.setAttribute('aria-label', open ? 'Rubriken schließen' : 'Rubriken anzeigen');
      if (open) openedAt = window.scrollY;
    };
    btn.addEventListener('click', () => setOpen(!top.classList.contains('is-open')));
    // Nach dem Sprung zuklappen, damit der Zielabschnitt frei liegt
    $$('a[href^="#"]', panel).forEach(a => a.addEventListener('click', () => setOpen(false)));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && top.classList.contains('is-open')) {
        setOpen(false);
        btn.focus();
      }
    });
    window.addEventListener('resize', () => {
      if (window.innerWidth >= 840 && top.classList.contains('is-open')) setOpen(false);
    });
    // Wer weiterscrollt, will die Seite sehen – dann klappt das Menü zu
    window.addEventListener('scroll', () => {
      if (top.classList.contains('is-open') && Math.abs(window.scrollY - openedAt) > 30) setOpen(false);
    }, { passive: true });
    // Klick neben das Menü schließt es
    document.addEventListener('pointerdown', (e) => {
      if (!top.classList.contains('is-open')) return;
      if (e.target.closest('#navPanel, #menu')) return;
      setOpen(false);
    });
  }

  /* ------------------------------------------------------------------
     6. Bewegung im Kopfbereich: Neigung zur Maus, leichte Schnittbewegung
     ------------------------------------------------------------------ */
  function heroMotion() {
    const wall = $('#wall');
    const frame = wall && $('.wall__frame', wall);
    if (!wall || !frame || reduce) return;

    // Neigung zur Zeigerposition (max. gut 5 Grad – mehr wirkt billig).
    // Die Bewegung läuft nur, solange sich etwas ändert – kein Dauer-rAF.
    let tx = 0, ty = 0, cx = 0, cy = 0, running = false;
    const tick = () => {
      cx += (tx - cx) * 0.08;
      cy += (ty - cy) * 0.08;
      frame.style.setProperty('--tilt-y', cx.toFixed(3) + 'deg');
      frame.style.setProperty('--tilt-x', cy.toFixed(3) + 'deg');
      if (Math.abs(tx - cx) > 0.01 || Math.abs(ty - cy) > 0.01) {
        requestAnimationFrame(tick);
      } else { running = false; }
    };
    const kick = () => { if (!running) { running = true; requestAnimationFrame(tick); } };

    const hero = $('#hero');
    if (hero) {
      hero.addEventListener('pointermove', (e) => {
        if (e.pointerType === 'touch') return;
        const r = hero.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        tx = px * 5.5;
        ty = -py * 4.5;
        kick();
      });
      hero.addEventListener('pointerleave', () => { tx = 0; ty = 0; kick(); });
    }

    // leichte Schnittbewegung beim Scrollen
    let sraf = 0;
    const onScroll = () => {
      if (sraf) return;
      sraf = requestAnimationFrame(() => {
        sraf = 0;
        const y = window.scrollY;
        if (y < window.innerHeight * 1.2) {
          wall.style.transform = 'translate3d(0,' + (y * 0.05).toFixed(2) + 'px,0)';
        }
      });
    };
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  /* ------------------------------------------------------------------
     7. Änderungsprotokoll
     ------------------------------------------------------------------ */
  const LOG = [
    {
      v: '1.31', de: 'SVG und SVGZ', en: 'SVG and SVGZ',
      de_items: [
        'SVG-Dateien werden angezeigt – Android kann das nicht von sich aus, dafür ist ein SVG-Renderer eingebaut.',
        'Vektorgrafik wird in der jeweils gebrauchten Größe gezeichnet: in jeder Größe scharf, sehr sparsam im Speicher.',
        'Formatecke „SVG“, eigene Gruppe im Format-Tab; Bearbeiten speichert als JPEG-Kopie.'
      ],
      en_items: [
        'SVG files are displayed – Android cannot do this on its own, so an SVG renderer is built in.',
        'Vector art is drawn at the size actually needed: sharp at any size, very memory friendly.',
        'SVG corner badge, its own group in the format tab; editing saves a JPEG copy.'
      ]
    },
    {
      v: '1.30', de: 'Zähler korrekt, Scrollen ruhig', en: 'Correct counts, calmer scrolling',
      de_items: [
        'Ordnergrößen zeigten „0 Dateien“ – die Textvorlage war einmal mit 0 verbraucht worden. Jetzt steht überall die richtige Zahl.',
        'Vorschauen wurden im Haupt-Thread ausgepackt – genau das ruckelte beim Wischen. Passiert jetzt im Hintergrund.',
        'Vorladen läuft nur noch, wenn der Finger ruht; sichtbare Kacheln haben Vorrang.'
      ],
      en_items: [
        'Folder sizes showed “0 files” – the text template had been consumed with 0 once. Everywhere now shows the real number.',
        'Previews were unpacked on the main thread – that is exactly what stuttered while swiping. Now happens in the background.',
        'Prefetch only runs while the finger rests; visible tiles take priority.'
      ]
    },
    {
      v: '1.29', de: 'Löschen erreichbar', en: 'Delete within reach',
      de_items: [
        'Papierkorb in der Kopfzeile der Großansicht – vorher war der Knopf das letzte Element einer seitlich scrollbaren Reihe im Blatt.',
        'Im Blatt steht „Löschen“ jetzt vorne und rot; zusätzlich per Langdruck im Raster.',
        'Rückfrage plus Android-Systembestätigung.'
      ],
      en_items: [
        'Trash icon in the viewer header – previously the button was the last item of a horizontally scrolling row inside the sheet.',
        'Inside the sheet “Delete” now sits first and in red; also via long press in the grid.',
        'Confirmation plus Android system approval.'
      ]
    },
    {
      v: '1.28', de: 'Updates installieren wieder', en: 'Updates install again',
      de_items: [
        'Jeder Build war mit einem anderen Schlüssel signiert (der Buildserver erzeugt seinen Debug-Schlüssel jedes Mal neu) – Android lehnte jedes Update ab. Jetzt ein fester Schlüssel für alle Builds.',
        'Der Build prüft den Fingerabdruck selbst und bricht ab, wenn er abweicht.',
        'Notizen, Tags und Favoriten werden zusätzlich außerhalb der App gesichert und automatisch zurückgeholt.'
      ],
      en_items: [
        'Every build was signed with a different key (the runner regenerates its debug key each time) – Android rejected every update. Now one fixed key for all builds.',
        'The build verifies the fingerprint itself and fails if it differs.',
        'Notes, tags and favourites are additionally backed up outside the app and restored automatically.'
      ]
    },
    {
      v: '1.27', de: 'Speichern-Knopf, HEIC schneller', en: 'Save button, faster HEIC',
      de_items: [
        'Speichern war nur ein kleines Häkchen ohne Text – jetzt ein beschrifteter Knopf und ein Dialog mit echten Knöpfen.',
        'Der System-Decoder schaltete sich nach drei Fehlversuchen für die ganze Sitzung ab; danach liefen alle HEICs über den langsamen Software-Weg. Jetzt wird das pro Datei gemerkt.',
        'Dunkle Hardware-Ergebnisse werden behalten statt verworfen; kein zweites Dekodieren derselben Datei beim Öffnen.'
      ],
      en_items: [
        'Saving was a small unlabelled check mark – now a labelled button and a dialog with real buttons.',
        'The system decoder disabled itself for the whole session after three failures; after that every HEIC took the slow software path. Now remembered per file.',
        'Dark hardware results are kept instead of discarded; no second decode of the same file on open.'
      ]
    },
    {
      v: '1.26', de: 'Speichern repariert, Vorschauen im RAM', en: 'Saving fixed, previews in RAM',
      de_items: [
        'Nach dem Schreiben wird nachgesehen, ob Daten angekommen sind; misslungene Einträge werden entfernt. Der unsichtbare app-interne Ordner ist weg.',
        'Vorschauen liegen zusätzlich komprimiert im Arbeitsspeicher – rund 25× mehr Bilder passen hinein.',
        'Nur die sichtbare Seite lädt beim Öffnen das Vollbild; vorher dekodierten drei Seiten gleichzeitig.'
      ],
      en_items: [
        'After writing, the app checks whether data arrived; failed entries are removed. The invisible app-private folder is gone.',
        'Previews are additionally kept compressed in memory – roughly 25× more images fit.',
        'Only the visible page loads the full image; previously three pages decoded at once.'
      ]
    },
    {
      v: '1.25', de: 'Scharfe Kacheln, Hintergrund-Aufbau', en: 'Sharp tiles, background build',
      de_items: [
        'Kein unscharfes Aufblühen mehr: Kacheln erscheinen in echter Kachelgröße.',
        'Größenstufen 64/128/256/512/1024 px – kleine Kacheln lesen 16× weniger Pixel.',
        'Ein Hintergrund-Daemon erzeugt Vorschauen für die ganze Bibliothek und pausiert beim Wischen.'
      ],
      en_items: [
        'No more blur-up: tiles appear at their real tile size.',
        'Size buckets 64/128/256/512/1024 px – small tiles read 16× fewer pixels.',
        'A background daemon builds previews for the whole library and pauses while scrolling.'
      ]
    },
    {
      v: '1.24', de: 'Apple-Zuschnitt, robustes Speichern', en: 'Apple-style crop, robust saving',
      de_items: [
        'Zuschneiden wie in der Apple-Fotos-App: der Rahmen steht fest, das Foto bewegt sich darunter.',
        'Drei Speicherwege nacheinander, klare Fehlermeldungen, Schreibfreigabe auch auf Android 8/9.'
      ],
      en_items: [
        'Cropping like Apple Photos: the frame stays put, the photo moves underneath.',
        'Three save paths one after another, clear error messages, write permission also on Android 8/9.'
      ]
    },
    {
      v: '1.23 / 1.22', de: 'Tippen und Scrollen', en: 'Tapping and scrolling',
      de_items: [
        'Tippen öffnet jetzt immer das angetippte Foto – Position und Bildadresse werden zusammen übergeben.',
        'Raster-Aufbau im Hintergrund, Datumsformatierung mit Zwischenspeicher: kein sekundenlanges Hängen mehr.',
        'Foto-Editor mit Zuschneiden, Zeichnen und Textfeldern.'
      ],
      en_items: [
        'Tapping always opens the photo that was tapped – position and image address are passed together.',
        'Grid built in the background, date formatting cached: no more freezing for seconds.',
        'Photo editor with crop, draw and text layers.'
      ]
    }
  ];

  function buildLog() {
    const host = $('#logList');
    if (!host) return;
    const en = document.documentElement.lang === 'en';
    host.textContent = ''; // Hinweis für den Fall ohne Skript entfernen
    host.innerHTML = LOG.map((entry, i) => {
      const open = i === 0;
      const items = (en ? entry.en_items : entry.de_items).map(t => `<li>${t}</li>`).join('');
      return `
        <div class="entry">
          <button class="entry__btn" type="button" aria-expanded="${open}" aria-controls="log-panel-${i}">
            <span class="entry__v">${entry.v}</span>
            <span class="entry__t">${en ? entry.en : entry.de}</span>
            <span class="entry__mark" aria-hidden="true"></span>
          </button>
          <div class="entry__panel" id="log-panel-${i}">
            <div class="entry__inner"><ul>${items}</ul></div>
          </div>
        </div>`;
    }).join('');

    $$('.entry__btn', host).forEach(btn => {
      btn.addEventListener('click', () => {
        btn.setAttribute('aria-expanded', btn.getAttribute('aria-expanded') === 'true' ? 'false' : 'true');
      });
    });
  }

  /* ------------------------------------------------------------------
     8. Zweisprachigkeit.
     Deutsch steht im HTML; für Englisch gibt es ein Wörterbuch.
     Die deutsche Fassung wird beim Laden aus der Seite gelesen – so kann
     beim Umschalten nichts auseinanderlaufen.
     ------------------------------------------------------------------ */
  const EN = {
    'nav.decoder': 'Engineering',
    'nav.formats': 'Formats',
    'nav.speed': 'Speed',
    'nav.features': 'Daily use',
    'nav.privacy': 'Offline',
    'nav.install': 'Install',
    'nav.log': 'Changelog',
    'cta.download': 'Download APK',
    'cta.downloadShort': 'Download',
    'cta.log': "What's new",
    'cta.demo': 'How it works',
    'cta.testApk': 'TEST build (installs alongside)',
    'hero.kicker': 'Android 8.0+ · no internet permission',
    'hero.h1a': 'Apple screenshots.',
    'hero.h1b': 'Finally on Android.',
    'hero.lead': 'N3 Gallery opens Apple’s tiled HEIC containers – including 10-bit, HDR and gain maps. Plus 35 RAW formats, AVIF, SVG and HEVC video. Entirely offline.',
    'hero.note': 'Version 1.31 · 29 MB · open source',
    'hero.f1': 'No internet access',
    'hero.f2': 'No accounts',
    'hero.f3': 'No ads',
    'hero.f4': 'Verifiable signature',
    'wall.hint': 'Hover to inspect',
    'stat.k1': 'file extensions the app recognises – from heic to x3f',
    'stat.k2': 'camera RAW formats via the embedded preview',
    'stat.u3': 'columns',
    'stat.k3': 'Grid up to 32 columns – two fingers in the grid is all it takes',
    'stat.k4': 'internet permissions in the app. Not one.',
    'decoder.kicker': 'The reason this app exists',
    'decoder.h2': 'An iPhone photo is not a single image. It is a container of tiles.',
    'decoder.p': 'Screenshots and many iPhone photos are stored as a tiled grid – for example 8 × 6 tiles of 512 px, often 10-bit or with a gain map for HDR. The standard Android path rejects exactly that. So N3 Gallery works through the container in five stages – and remembers per file which stage worked.',
    'step1.t': 'Android system path',
    'step1.d': 'For ordinary JPEG, PNG, WebP, GIF, BMP, TIFF and simple HEICs. Tiled grids and 10-bit HEVC often end up black or empty here.',
    'step2.t': 'Hardware HEVC (Android 10+)',
    'step2.d': 'The device’s HEVC block decodes with the same technology as the iPhone – so it goes first. Results that are merely black are detected and not shown.',
    'step3.t': 'libheif inside the app',
    'step3.d': 'A dedicated HEIF decoder for tiled, 10-bit, 12-bit and HDR containers: the image is assembled tile by tile.',
    'step4.t': 'Embedded camera preview',
    'step4.d': 'For RAW (CR3, NEF, ARW, ORF, RW2, RAF, X3F …) the largest JPEG preview embedded in the raw file is read – instantly and at full quality.',
    'step5.t': 'Systematic search inside the container',
    'step5.d': 'For exotics the app looks for JPEG data anywhere in the container and shows it instead of giving up.',
    'formats.kicker': 'Scope',
    'formats.h2': '79 file extensions. One list.',
    'formats.caption': 'Recognised by file type and extension – also in directories added via “Add folder” (SD card, downloads, NAS sync).',
    'formats.h.group': 'Group',
    'formats.h.count': 'Count',
    'formats.h.examples': 'Examples',
    'formats.h.note': 'What is special',
    'formats.r1': 'Tiled grid, 10-bit, HDR, gain map – the core of this app',
    'formats.g.raw': 'Camera RAW',
    'formats.r2': 'via the embedded camera preview, not interpolated',
    'formats.g.avif': 'AVIF',
    'formats.r3': 'dedicated AV1 decoder, independent of the Android version',
    'formats.g.vector': 'Vector art',
    'formats.r4': 'drawn at the exact size needed – sharp at any size',
    'formats.g.classic': 'Classics',
    'formats.r5': 'system path, with the app decoder as fallback',
    'formats.g.video': 'Video',
    'formats.r6': 'still frame via HEVC/H.264, including container formats',
    'speed.kicker': 'Speed',
    'speed.h2': 'Speed is a decision, not an accident.',
    'speed.p': 'The numbers below are not estimates: they are the changes measured in recent versions. Every line is in the changelog like this.',
    'm1.name': 'Pixels per grid tile',
    'm1.delta': '16× fewer',
    'm2.name': 'Memory per preview',
    'm2.delta': '~25× more images in the same RAM',
    'm2.v1': '1.0 MB',
    'm3.name': 'Decode operations when opening a photo',
    'm3.delta': 'no duplicated work',
    'm4.name': 'Previews after an app restart',
    'm4.delta': 'stored permanently',
    'm.before': 'before',
    'm.now': 'now',
    'speed.foot': 'The background build creates previews at tile size and pauses while a finger is scrolling – the visible images get the CPU.',
    'features.kicker': 'Daily use',
    'features.h2': 'Small things you notice every day.',
    'f1.t': 'Cropping like Apple Photos',
    'f1.d': 'The frame stays put, the photo moves underneath. Eight handles with 30 dp touch targets, aspect ratios always fill the crop completely – no black edges in the result.',
    'f2.t': 'Saving that checks itself',
    'f2.d': 'After writing, the app verifies that data actually arrived; failed gallery entries are removed instead of left as empty images. As a last resort a share dialog – nothing is lost.',
    'f3.t': 'Delete reachable everywhere',
    'f3.d': 'Trash icon in the header, in the detail sheet and via long press in the grid – with confirmation and Android’s system approval.',
    'f4.t': 'Write notes and tags into the file',
    'f4.d': 'As real EXIF/XMP metadata – not just in the app database. Plus an automatic backup outside the app, so a reinstall costs nothing.',
    'f5.t': 'Views by day, format, folder and album',
    'f5.d': 'Timeline by day, month or year; tags and favourites as albums; formats and folders with counts and size.',
    'f6.t': 'Complete metadata',
    'f6.d': 'EXIF, sensor, lens, GPS (uncensored with permission) and XMP – copyable as text.',
    'f7.t': 'Two languages, one layout',
    'f7.d': 'German and English, following the rules of each language – including date and size formats.',
    'f8.t': 'Large libraries stay calm',
    'f8.d': 'Previews are created once and kept; the grid builds its rows in the background so swiping and tapping always come first.',
    'privacy.kicker': 'Offline',
    'privacy.claim': 'The app requests <em>no internet permission</em>. That is not a promise, it is a line that is missing from the manifest.',
    'privacy.c1': 'No <code>android.permission.INTERNET</code> in the manifest – verifiable right inside the APK.',
    'privacy.c2': 'No account, no sign-in, no cloud, no server.',
    'privacy.c3': 'No ads, no telemetry, no analytics SDK.',
    'privacy.c4': 'Notes, tags and favourites are additionally backed up outside the app – a reinstall costs nothing.',
    'privacy.verify': 'Check for yourself: <code class="mono">aapt dump badging N3-Gallery-1.31-release.apk | grep uses-permission</code>',
    'install.kicker': 'Installation',
    'install.h2': 'Three steps onto the device.',
    'install.p': 'The app is not distributed through a store: the finished APK comes straight from the build and the signing key stays the same. That is why new versions can simply be laid over the installed one.',
    'i1.t': 'Download the APK',
    'i1.d': 'From the release page: the regular build or the TEST build. The TEST build installs alongside (its own identifier) and leaves your existing app untouched.',
    'i2.t': 'Allow installation',
    'i2.d': 'Android asks once for permission for this source – confirm once, afterwards it behaves like any other app.',
    'i3.t': 'Read in your library',
    'i3.d': 'The app reads the media index. For directories outside it (SD card, downloads, NAS), pick “Add folder” in the folders tab.',
    'foot.sum': 'Signature and updates – technical details',
    'foot.sig': 'All builds are signed with the same key (SHA-256):',
    'foot.sig2': 'Starting with version 1.28 every version installs over the same identifier – updates arrive without uninstalling. If a different fingerprint shows up, it is not this build.',
    'foot.about': 'An Android gallery for Apple HEIF containers, RAW, AVIF, SVG and HEVC – built by N3 Vibecode. No cloud, no account, no network access.',
    'foot.dl': 'Download',
    'foot.dl1': 'Latest version (APK)',
    'foot.dl2': 'All releases',
    'foot.dl3': 'Source code (GitHub)',
    'foot.proj': 'Project',
    'foot.p1': 'Changelog',
    'foot.p2': 'Signing &amp; your own key',
    'foot.p3': 'Licence (GNU GPL v3)',
    'foot.note': 'Static page: no scripts from outside, no web fonts, no tracking.',
    'demo.caption': 'Schematic: the same photo through all five paths – the window on the left follows every step.',
    'log.kicker': 'Changelog',
    'log.h2': 'What happened recently.',
    'meta.title': 'N3 Gallery – Apple screenshots finally on Android',
    'meta.description': 'N3 Gallery opens Apple’s tiled HEIC containers, 10-bit, HDR, 35 RAW formats, AVIF, SVG and HEVC video. Fully offline – without internet permission.'
  };

  const DE = {};
  function snapshotGerman() {
    $$('[data-i18n]').forEach(el => { DE[el.dataset.i18n] = el.innerHTML.trim(); });
  }

  function applyLang(lang) {
    const en = lang === 'en';
    document.documentElement.lang = lang;
    $$('[data-i18n]').forEach(el => {
      const key = el.dataset.i18n;
      const value = en ? EN[key] : DE[key];
      if (value == null) return;
      if (/<[a-z/]/i.test(value)) el.innerHTML = value;
      else el.textContent = value;
    });
    const btn = $('#lang');
    if (btn) btn.textContent = en ? 'DE' : 'EN';
    if (en) {
      document.title = EN['meta.title'];
      const md = document.querySelector('meta[name="description"]');
      if (md) md.setAttribute('content', EN['meta.description']);
    } else {
      document.title = 'N3 Gallery – Apple-Screenshots endlich auf Android';
      const md = document.querySelector('meta[name="description"]');
      if (md) md.setAttribute('content', 'N3 Gallery öffnet Apples HEIC-Container mit Kachel-Raster, 10-Bit, HDR, 35 RAW-Formate, AVIF, SVG und HEVC-Video. Vollständig offline – ohne Internet-Berechtigung.');
    }
    const readout = $('#readout');
    if (readout) readout.textContent = DEFAULT_READOUT();
    setHint();
    if (stageIndex >= 0) setStage(stageIndex, true); // Beschriftung des Fensters nachziehen
    buildLog();
    try { localStorage.setItem('n3.lang', lang); } catch (_) {}
  }

  /* ------------------------------------------------------------------
     9. Start
     ------------------------------------------------------------------ */
  function boot() {
    buildWall();
    buildBand();
    buildViewer();
    snapshotGerman();

    // Zuerst die Sprache des Browsers, dann eine frühere Auswahl.
    let lang = 'de';
    let saved = null;
    try { saved = localStorage.getItem('n3.lang'); } catch (_) {}
    if (saved === 'de' || saved === 'en') lang = saved;
    else if ((navigator.language || '').toLowerCase().startsWith('en')) lang = 'en';
    if (lang !== 'de') applyLang(lang); // schreibt die Wahl auch in den Speicher
    else if (saved) { try { localStorage.setItem('n3.lang', lang); } catch (_) {} }
    buildLog();

    const langBtn = $('#lang');
    if (langBtn) {
      langBtn.addEventListener('click', () => {
        applyLang(document.documentElement.lang === 'en' ? 'de' : 'en');
      });
    }

    // Kopfzeilen-Animation und Zusammenbau der Kachelwand starten
    requestAnimationFrame(() => {
      document.body.classList.add('hero-ready');
      const wall = $('#wall');
      if (wall) setTimeout(() => wall.classList.add('is-assembled'), reduce ? 0 : 140);
    });

    observeReveal();
    countUp();
    observeChain();
    scrollChrome();
    mobileMenu();
    heroMotion();

    const year = $('#year');
    if (year) year.textContent = String(new Date().getFullYear());
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
