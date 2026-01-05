"""
Gelişmiş IPTV Link Kontrol Aracı
- Asenkron toplu kontrol (aiohttp) -> binlerce link hızlıca kontrol edebilir
- Konfigüre edilebilir paralellik, timeout, retry mekanizması, user-agent, proxy desteği (opsiyonel)
- Hafif / Derin mod: "Hafif" mod sadece m3u dosyası ve ilk kanallar için hızlı HTTP kontrolü yapar; "Derin" mod isteğe bağlı ffprobe ile medya doğrulaması dener (ffprobe sistemde yoksa atlar)
- VLC ile seçili link için canlı önizleme (tekil seçimlerde)
- Tkinter GUI: dosya seçme, yapıştırma, filtre, ilerleme, log, durdur, dışa aktar

Gereksinimler:
pip install aiohttp aiodns cchardet
pip install python-vlc   # Önizleme gerekiyorsa
(Optional) ffmpeg/ffprobe sistem PATH'inde olursa daha derin doğrulama yapılır.

Not: Bu betik GUI'den bağımsız olarak yüksek performanslı asenkron istekler kullanır.
"""

import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import re
import asyncio
import aiohttp
import threading
import time
import webbrowser
import sys
import os
import subprocess
import json
from urllib.parse import urlparse

# VLC opsiyonel - eğer yüklü değilse sadece önizleme devre dışı kalır
try:
    import vlc
    VLC_AVAILABLE = True
except Exception:
    VLC_AVAILABLE = False

# ------------------------- Yardımcı Fonksiyonlar -------------------------

def is_ffprobe_available():
    try:
        subprocess.run(["ffprobe", "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


# Basit, güvenli URL çıkarma (m3u, m3u8, vs.)
URL_PATTERN = re.compile(r"https?://[\w\-\./?%&=+#~:,;@]+", re.IGNORECASE)


def extract_urls_from_text(text):
    return list({m.group(0) for m in URL_PATTERN.finditer(text)})


def filter_m3u_urls(urls):
    m3u_exts = ('.m3u', '.m3u8', 'm3u_plus')
    return [u for u in urls if any(ext in u.lower() for ext in m3u_exts)]


def parse_m3u(content):
    # Basit ama çoğu m3u'yu yakalar
    channels = []
    lines = [l.strip() for l in content.splitlines() if l.strip()]
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.upper().startswith('#EXTINF'):
            # #EXTINF:-1 tvg-id="" tvg-name="...",Channel Name
            parts = line.split(',', 1)
            name = parts[1].strip() if len(parts) > 1 else 'Unknown'
            url = None
            if i + 1 < len(lines):
                url_candidate = lines[i+1]
                if url_candidate.lower().startswith('http'):
                    url = url_candidate
                    i += 1
            channels.append((name, url))
        i += 1
    return channels


# ------------------------- Asenkron Kontrolör -------------------------

class AsyncIPTVChecker:
    def __init__(self, concurrency=200, timeout=10, retries=2, headers=None, proxy=None):
        self.concurrency = concurrency
        self.timeout = aiohttp.ClientTimeout(total=timeout)
        self.retries = retries
        self.headers = headers or {
            'User-Agent': 'Mozilla/5.0 (IPTVChecker/1.0)'
        }
        self.proxy = proxy
        self._semaphore = asyncio.Semaphore(concurrency)
        self._stop = False

    def stop(self):
        self._stop = True

    async def _fetch_text(self, session, url):
        # İndirme with retries
        last_exc = None
        for attempt in range(self.retries + 1):
            if self._stop:
                raise asyncio.CancelledError()
            try:
                async with session.get(url, timeout=self.timeout, headers=self.headers, proxy=self.proxy) as resp:
                    text = await resp.text(errors='ignore')
                    return resp.status, text
            except Exception as e:
                last_exc = e
                await asyncio.sleep(0.3 * (attempt + 1))
        raise last_exc

    async def _head_or_small_get(self, session, url):
        # Stream uygunluğunu test etmek için önce HEAD dene sonra küçük GET
        last_exc = None
        for attempt in range(self.retries + 1):
            if self._stop:
                raise asyncio.CancelledError()
            try:
                # Önce HEAD isteği
                async with session.head(url, timeout=self.timeout, headers=self.headers, proxy=self.proxy) as resp:
                    if resp.status == 200:
                        c_type = resp.headers.get('Content-Type', '')
                        return True, resp.status, c_type
                # Bazı sunucular HEAD'i desteklemez -> küçük GET
                async with session.get(url, timeout=self.timeout, headers=self.headers, proxy=self.proxy) as resp:
                    if resp.status == 200:
                        c_type = resp.headers.get('Content-Type', '')
                        # Okunacak küçük bir parça
                        await resp.content.read(1024)
                        return True, resp.status, c_type
                    else:
                        return False, resp.status, None
            except Exception as e:
                last_exc = e
                await asyncio.sleep(0.2 * (attempt + 1))
        raise last_exc

    async def check_m3u(self, url, max_channel_check=3, deep=False):
        # returns dict with details
        if self._stop:
            return {'url': url, 'status': 'cancelled'}
        async with self._semaphore:
            async with aiohttp.ClientSession(timeout=self.timeout) as session:
                try:
                    status, text = await self._fetch_text(session, url)
                except Exception as e:
                    return {'url': url, 'ok': False, 'error': f'Fetch error: {e}'}

                if status != 200 or '#EXTM3U' not in (text or ''):
                    return {'url': url, 'ok': False, 'status_code': status, 'error': 'Not a valid M3U or HTTP != 200'}

                channels = parse_m3u(text)
                if not channels:
                    return {'url': url, 'ok': False, 'channels': [], 'error': 'No channels found in M3U'}

                # Hızlı: ilk N kanalı küçük GET ile test et
                num_to_test = min(max_channel_check, len(channels))
                tested = []
                for idx, (name, stream_url) in enumerate(channels[:num_to_test], start=1):
                    if not stream_url:
                        tested.append((name, stream_url, False, 'No URL'))
                        continue
                    try:
                        ok, st, ctype = await self._head_or_small_get(session, stream_url)
                        tested.append((name, stream_url, ok, f'status={st}, type={ctype}'))
                        if ok and not deep:
                            # Eğer hafif mod ve bir kanal çalışıyorsa linki kabul et
                            return {'url': url, 'ok': True, 'channels': channels, 'tested': tested}
                        elif ok and deep:
                            # Derin doğrulama: ffprobe varsa çalıştır
                            if is_ffprobe_available():
                                probe_ok = await asyncio.get_event_loop().run_in_executor(None, self._ffprobe_check, stream_url)
                                if probe_ok:
                                    return {'url': url, 'ok': True, 'channels': channels, 'tested': tested}
                            else:
                                # ffprobe yoksa ilk ok kabul
                                return {'url': url, 'ok': True, 'channels': channels, 'tested': tested}
                    except Exception as e:
                        tested.append((name, stream_url, False, str(e)))

                # Eğer buraya geldiyse ilk N kanal çalışmadı
                return {'url': url, 'ok': False, 'channels': channels, 'tested': tested, 'error': 'No working channels in tested set'}

    def _ffprobe_check(self, stream_url):
        # ffprobe ile kısa bir doğrulama (blocking, threadpool içinde çağrılmalı)
        try:
            cmd = [
                'ffprobe',
                '-v', 'error',
                '-show_entries', 'stream=codec_type,codec_name',
                '-of', 'json',
                stream_url
            ]
            p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            if p.returncode == 0:
                # Basit parse
                out = p.stdout.decode('utf-8', errors='ignore')
                j = json.loads(out) if out else {}
                streams = j.get('streams', [])
                # ideal: contains video or audio
                if any(s.get('codec_type') in ('video','audio') for s in streams):
                    return True
            return False
        except Exception:
            return False

# ------------------------- GUI Uygulaması -------------------------

class AdvancedIPTVCheckerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('Gelişmiş IPTV Link Kontrol Aracı')
        self.geometry('1200x800')
        self.configure(bg='#222')

        # Varsayılan tercih
        self.prefs = {
            'timeout': 8,
            'concurrency': 200,
            'retries': 2,
            'channels_to_test': 3,
            'deep_check': False,
            'user_agent': 'Mozilla/5.0 (IPTVChecker/Advanced)',
            'proxy': None
        }

        self.loop_thread = None
        self.loop = None
        self.checker = None
        self.worker_task = None
        self.stop_event = threading.Event()
        self.link_results = {}  # url -> result dict

        self.create_widgets()

    def create_widgets(self):
        top = tk.Frame(self, bg='#333')
        top.pack(fill=tk.X, padx=8, pady=6)

        tk.Button(top, text='Dosya Seç (.txt veya .m3u)', command=self.select_file, bg='#4caf50', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Yapıştırtan Al', command=self.paste_input, bg='#2196f3', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Linkleri Bul', command=self.find_links_from_input, bg='#009688', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Kontrol Başlat', command=self.start_check, bg='#f44336', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Durdur', command=self.stop_check, bg='#ff9800', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Dışa Aktar (Çalışanlar)', command=self.export_working, bg='#607d8b', fg='white').pack(side=tk.LEFT, padx=4)
        tk.Button(top, text='Tercihler', command=self.open_preferences, bg='#795548', fg='white').pack(side=tk.LEFT, padx=4)

        # Orta bölüm: giriş/çıktı ve log
        mid = tk.PanedWindow(self, orient=tk.HORIZONTAL)
        mid.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)

        left = tk.Frame(mid, bg='#2b2b2b')
        mid.add(left, stretch='always')
        right = tk.Frame(mid, bg='#1f1f1f', width=380)
        mid.add(right, stretch='never')

        # Girdi alanı
        tk.Label(left, text='Girdi (dosya veya yapıştırılmış içerik):', bg='#2b2b2b', fg='white').pack(anchor='w', padx=6, pady=4)
        self.text_input = tk.Text(left, height=10, bg='#121212', fg='white')
        self.text_input.pack(fill=tk.X, padx=6)

        # Link listeleri
        lists = tk.Frame(left, bg='#2b2b2b')
        lists.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)

        lb_frame = tk.Frame(lists, bg='#2b2b2b')
        lb_frame.pack(fill=tk.BOTH, expand=True)

        tk.Label(lb_frame, text='Tespit Edilen M3U Linkler', bg='#2b2b2b', fg='white').pack(anchor='w')
        self.lb_links = tk.Listbox(lb_frame, bg='#111', fg='white')
        self.lb_links.pack(fill=tk.BOTH, expand=True, side=tk.LEFT)
        sc = tk.Scrollbar(lb_frame, command=self.lb_links.yview)
        sc.pack(side=tk.LEFT, fill=tk.Y)
        self.lb_links.config(yscrollcommand=sc.set)

        right_inner = tk.Frame(right, bg='#121212')
        right_inner.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)
        tk.Label(right_inner, text='Sistem Log', bg='#121212', fg='lime').pack(anchor='w')
        self.log = tk.Text(right_inner, bg='#000', fg='lime', height=20)
        self.log.pack(fill=tk.BOTH, expand=True)

        # İlerleme
        bottom = tk.Frame(self, bg='#222')
        bottom.pack(fill=tk.X, padx=8, pady=6)
        self.progress = ttk.Progressbar(bottom, length=600)
        self.progress.pack(side=tk.LEFT, padx=6)
        self.progress_label = tk.Label(bottom, text='0/0', bg='#222', fg='white')
        self.progress_label.pack(side=tk.LEFT, padx=6)

        # Sağ alt: çalışma/başarısız listeleri
        results_frame = tk.Frame(self, bg='#2b2b2b')
        results_frame.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)

        left_res = tk.Frame(results_frame, bg='#2b2b2b')
        left_res.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        tk.Label(left_res, text='Çalışan Linkler', bg='#2b2b2b', fg='white').pack(anchor='w')
        self.lb_working = tk.Listbox(left_res, bg='#082', fg='white')
        self.lb_working.pack(fill=tk.BOTH, expand=True)
        self.lb_working.bind('<Double-Button-1>', self.preview_selected)

        right_res = tk.Frame(results_frame, bg='#2b2b2b')
        right_res.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        tk.Label(right_res, text='Başarısız Linkler', bg='#2b2b2b', fg='white').pack(anchor='w')
        self.lb_failed = tk.Listbox(right_res, bg='#820', fg='white')
        self.lb_failed.pack(fill=tk.BOTH, expand=True)

    # ---------------- GUI yardımcıları ----------------
    def log_message(self, text):
        ts = time.strftime('%H:%M:%S')
        self.log.insert(tk.END, f'[{ts}] {text}\n')
        self.log.see(tk.END)

    def select_file(self):
        p = filedialog.askopenfilename(filetypes=[('Text/M3U files', '*.txt;*.m3u;*.m3u8')])
        if not p:
            return
        try:
            with open(p, 'r', encoding='utf-8', errors='ignore') as f:
                data = f.read()
            self.text_input.delete('1.0', tk.END)
            self.text_input.insert(tk.END, data)
            self.log_message(f'Yüklendi: {p}')
        except Exception as e:
            messagebox.showerror('Hata', f'Dosya okunamadı: {e}')

    def paste_input(self):
        # Pencereye yapıştırmak için kullanıcıya kısayol göster
        messagebox.showinfo('Yapıştır', 'Metni panoya kopyalayıp OK ile yapıştırabilirsiniz.')
        try:
            clipboard = self.clipboard_get()
            self.text_input.insert(tk.END, clipboard)
            self.log_message('Panodan yapıştırıldı')
        except Exception:
            self.log_message('Panodan alınamadı veya boş.')

    def find_links_from_input(self):
        text = self.text_input.get('1.0', tk.END)
        urls = extract_urls_from_text(text)
        m3u_urls = filter_m3u_urls(urls)
        self.lb_links.delete(0, tk.END)
        for u in m3u_urls:
            self.lb_links.insert(tk.END, u)
        self.log_message(f'{len(m3u_urls)} adet M3U linki bulundu')

    # ----------------- Asenkron iş kontrol -----------------
    def start_check(self):
        links = list(self.lb_links.get(0, tk.END))
        if not links:
            messagebox.showwarning('Uyarı', 'Kontrol edilecek link yok')
            return
        # Tercihlerden checker oluştur
        headers = {'User-Agent': self.prefs.get('user_agent')}
        proxy = self.prefs.get('proxy')
        concurrency = int(self.prefs.get('concurrency'))
        timeout = int(self.prefs.get('timeout'))
        retries = int(self.prefs.get('retries'))
        deep = bool(self.prefs.get('deep_check'))
        channels_to_test = int(self.prefs.get('channels_to_test'))

        self.checker = AsyncIPTVChecker(concurrency=concurrency, timeout=timeout, retries=retries, headers=headers, proxy=proxy)
        self.stop_event.clear()
        self.lb_working.delete(0, tk.END)
        self.lb_failed.delete(0, tk.END)
        self.link_results.clear()
        self.progress['value'] = 0
        total = len(links)
        self.progress_label.config(text=f'0/{total}')

        # Başlat: arka planda event loop thread
        def run_loop():
            asyncio.set_event_loop(asyncio.new_event_loop())
            loop = asyncio.get_event_loop()
            tasks = [self._run_check_one(link, channels_to_test, deep) for link in links]
            loop.run_until_complete(asyncio.gather(*tasks))
            loop.close()
            # tamamlandı
            self.after(0, lambda: self.log_message('Tüm kontroller tamamlandı.'))

        t = threading.Thread(target=run_loop, daemon=True)
        t.start()

    async def _run_check_one(self, link, channels_to_test, deep):
        # wrapper to run checker.check_m3u and push results to GUI
        try:
            res = await self.checker.check_m3u(link, max_channel_check=channels_to_test, deep=deep)
        except asyncio.CancelledError:
            res = {'url': link, 'ok': False, 'error': 'Cancelled'}
        except Exception as e:
            res = {'url': link, 'ok': False, 'error': str(e)}
        # GUI update
        def gui_update():
            url = res.get('url')
            ok = res.get('ok', False)
            if ok:
                self.lb_working.insert(tk.END, url)
                self.log_message(f'OK: {url}')
            else:
                self.lb_failed.insert(tk.END, url)
                self.log_message(f'FAIL: {url} ({res.get("error")})')
            # progress
            current = self.lb_working.size() + self.lb_failed.size()
            total = self.lb_links.size()
            if total:
                self.progress['value'] = (current/total) * 100
                self.progress_label.config(text=f'{current}/{total}')
        self.after(0, gui_update)

    def stop_check(self):
        if self.checker:
            self.checker.stop()
            self.log_message('Durdurma talimatı gönderildi')
        self.stop_event.set()

    def export_working(self):
        items = list(self.lb_working.get(0, tk.END))
        if not items:
            messagebox.showwarning('Uyarı', 'Çalışan link yok')
            return
        p = filedialog.asksaveasfilename(defaultextension='.txt')
        if not p:
            return
        try:
            with open(p, 'w', encoding='utf-8') as f:
                for it in items:
                    f.write(it + '\n')
            messagebox.showinfo('Başarılı', 'Kaydedildi')
            self.log_message(f'Çalışan linkler kaydedildi: {p}')
        except Exception as e:
            messagebox.showerror('Hata', f'Kaydedilemedi: {e}')

    def open_preferences(self):
        win = tk.Toplevel(self)
        win.title('Tercihler')
        win.geometry('420x360')
        tk.Label(win, text='Concurrency (eşzamanlı istek):').pack(anchor='w', padx=8, pady=4)
        e_conc = tk.Entry(win)
        e_conc.insert(0, str(self.prefs.get('concurrency')))
        e_conc.pack(fill=tk.X, padx=8)

        tk.Label(win, text='Timeout (s):').pack(anchor='w', padx=8, pady=4)
        e_timeout = tk.Entry(win)
        e_timeout.insert(0, str(self.prefs.get('timeout')))
        e_timeout.pack(fill=tk.X, padx=8)

        tk.Label(win, text='Retries:').pack(anchor='w', padx=8, pady=4)
        e_retries = tk.Entry(win)
        e_retries.insert(0, str(self.prefs.get('retries')))
        e_retries.pack(fill=tk.X, padx=8)

        tk.Label(win, text='Denenecek Kanal Sayısı:').pack(anchor='w', padx=8, pady=4)
        e_channels = tk.Entry(win)
        e_channels.insert(0, str(self.prefs.get('channels_to_test')))
        e_channels.pack(fill=tk.X, padx=8)

        deep_var = tk.BooleanVar(value=self.prefs.get('deep_check'))
        tk.Checkbutton(win, text='Derin Kontrol (ffprobe varsa)', variable=deep_var).pack(anchor='w', padx=8, pady=6)

        tk.Label(win, text='User-Agent:').pack(anchor='w', padx=8, pady=4)
        e_ua = tk.Entry(win)
        e_ua.insert(0, self.prefs.get('user_agent'))
        e_ua.pack(fill=tk.X, padx=8)

        def save_prefs():
            try:
                self.prefs['concurrency'] = int(e_conc.get())
                self.prefs['timeout'] = int(e_timeout.get())
                self.prefs['retries'] = int(e_retries.get())
                self.prefs['channels_to_test'] = int(e_channels.get())
                self.prefs['deep_check'] = deep_var.get()
                self.prefs['user_agent'] = e_ua.get().strip() or self.prefs['user_agent']
                win.destroy()
                self.log_message('Tercihler kaydedildi')
            except Exception as e:
                messagebox.showerror('Hata', f'Geçersiz değer: {e}')

        tk.Button(win, text='Kaydet', command=save_prefs, bg='#4caf50', fg='white').pack(pady=8)

    # ----------------- Önizleme -----------------
    def preview_selected(self, event):
        if not VLC_AVAILABLE:
            messagebox.showwarning('VLC yok', 'python-vlc yüklü değil veya VLC bulunamadı. Önizleme devre dışı.')
            return
        sel = self.lb_working.curselection()
        if not sel:
            return
        url = self.lb_working.get(sel[0])
        # M3U'dan kanalları çek ve ilk çalışan kanalı VLC ile oynat
        # Hafif: indirme ve parse
        def run_preview():
            try:
                import requests
                r = requests.get(url, timeout=10)
                if r.status_code != 200:
                    self.log_message('Önizleme: M3U alınamadı')
                    return
                chs = parse_m3u(r.text)
                for name, s_url in chs:
                    if not s_url:
                        continue
                    # VLC oynatmayı GUI thread dışı yap
                    inst = vlc.Instance()
                    player = inst.media_player_new()
                    media = inst.media_new(s_url)
                    player.set_media(media)
                    # platforma göre handle setleme basit
                    if sys.platform.startswith('win'):
                        player.set_hwnd(0)
                    player.play()
                    time.sleep(8)
                    state = player.get_state()
                    if state == vlc.State.Playing:
                        self.log_message(f'Önizleme: Çalışan kanal bulundu: {name}')
                        return
                    else:
                        player.stop()
                self.log_message('Önizleme: Çalışan kanal bulunamadı')
            except Exception as e:
                self.log_message(f'Önizleme hata: {e}')

        t = threading.Thread(target=run_preview, daemon=True)
        t.start()


if __name__ == '__main__':
    app = AdvancedIPTVCheckerApp()
    app.mainloop()
