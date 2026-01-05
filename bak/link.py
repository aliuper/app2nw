#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
URLScan.io Arama Aracı - Tam Versiyon
=====================================
Hem Terminal hem GUI modunda çalışır.
Eğitim ve güvenlik araştırmaları için tasarlanmıştır.

Kullanım:
    python urlscan_tool.py          # GUI modu
    python urlscan_tool.py --cli    # Terminal modu
"""

import requests
import json
import time
import os
import sys
import threading
from datetime import datetime
from urllib.parse import quote

# GUI imports
try:
    import tkinter as tk
    from tkinter import ttk, messagebox, filedialog, scrolledtext
    import webbrowser
    GUI_AVAILABLE = True
except ImportError:
    GUI_AVAILABLE = False


class URLScanAPI:
    """URLScan.io API işlemleri"""
    
    def __init__(self):
        self.base_url = "https://urlscan.io/api/v1/search/"
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Educational Research Tool)',
            'Accept': 'application/json'
        })
        self.all_results = []
        self.is_searching = False
        self.total_available = 0
        
    def search(self, query, max_results=500, callback=None):
        """
        URLScan.io'da arama yap
        
        Args:
            query: Arama sorgusu
            max_results: Maksimum sonuç sayısı
            callback: Her sayfa için çağrılacak fonksiyon (GUI için)
        
        Returns:
            list: Bulunan sonuçlar
        """
        self.all_results = []
        self.is_searching = True
        search_after = None
        page = 1
        self.total_available = 0
        
        while self.is_searching and len(self.all_results) < max_results:
            try:
                params = {
                    'q': query,
                    'size': 100
                }
                
                if search_after:
                    params['search_after'] = search_after
                
                response = self.session.get(self.base_url, params=params, timeout=30)
                
                if response.status_code == 429:
                    if callback:
                        callback('rate_limit', None)
                    time.sleep(30)
                    continue
                
                if response.status_code != 200:
                    if callback:
                        callback('error', f"HTTP {response.status_code}")
                    break
                
                data = response.json()
                results = data.get('results', [])
                
                # Toplam sonuç sayısı
                if self.total_available == 0:
                    self.total_available = data.get('total', 0)
                    if callback:
                        callback('total', self.total_available)
                
                if not results:
                    break
                
                # Sonuçları işle
                for result in results:
                    if len(self.all_results) >= max_results or not self.is_searching:
                        break
                    
                    result_data = {
                        'url': result.get('page', {}).get('url', ''),
                        'domain': result.get('page', {}).get('domain', ''),
                        'ip': result.get('page', {}).get('ip', ''),
                        'country': result.get('page', {}).get('country', ''),
                        'server': result.get('page', {}).get('server', ''),
                        'status': result.get('page', {}).get('status', ''),
                        'title': result.get('page', {}).get('title', ''),
                        'scan_id': result.get('_id', ''),
                        'scan_time': result.get('task', {}).get('time', ''),
                        'screenshot': f"https://urlscan.io/screenshots/{result.get('_id', '')}.png",
                        'result_url': f"https://urlscan.io/result/{result.get('_id', '')}/"
                    }
                    self.all_results.append(result_data)
                
                if callback:
                    callback('progress', {
                        'page': page,
                        'count': len(self.all_results),
                        'total': self.total_available,
                        'results': results
                    })
                
                # Sonraki sayfa için cursor
                if results:
                    last_result = results[-1]
                    sort_values = last_result.get('sort')
                    
                    if sort_values and len(sort_values) > 0:
                        search_after = ','.join(str(v) for v in sort_values)
                    else:
                        break
                else:
                    break
                
                if not data.get('has_more', False):
                    break
                
                page += 1
                time.sleep(0.5)
                
            except requests.exceptions.Timeout:
                if callback:
                    callback('timeout', None)
                time.sleep(5)
                continue
            except requests.exceptions.RequestException as e:
                if callback:
                    callback('error', str(e))
                break
            except json.JSONDecodeError:
                if callback:
                    callback('error', 'JSON parse hatası')
                break
        
        self.is_searching = False
        if callback:
            callback('complete', len(self.all_results))
        
        return self.all_results
    
    def stop(self):
        """Aramayı durdur"""
        self.is_searching = False
    
    def save_txt(self, filename):
        """Sadece URL'leri TXT olarak kaydet"""
        if not self.all_results:
            return False
        
        with open(filename, 'w', encoding='utf-8') as f:
            f.write(f"# URLScan.io Arama Sonuçları\n")
            f.write(f"# Tarih: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"# Toplam: {len(self.all_results)} URL\n")
            f.write("#" + "=" * 60 + "\n\n")
            
            for result in self.all_results:
                f.write(f"{result['url']}\n")
        
        return True
    
    def save_detailed_txt(self, filename):
        """Detaylı TXT raporu kaydet"""
        if not self.all_results:
            return False
        
        with open(filename, 'w', encoding='utf-8') as f:
            f.write("╔" + "═" * 78 + "╗\n")
            f.write("║" + " URLScan.io Detaylı Arama Raporu".center(78) + "║\n")
            f.write("╚" + "═" * 78 + "╝\n\n")
            f.write(f"📅 Rapor Tarihi: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"📊 Toplam Sonuç: {len(self.all_results)}\n")
            f.write("=" * 80 + "\n\n")
            
            for i, result in enumerate(self.all_results, 1):
                f.write(f"{'─' * 80}\n")
                f.write(f"📌 Sonuç #{i}\n")
                f.write(f"{'─' * 80}\n")
                f.write(f"🔗 URL      : {result['url']}\n")
                f.write(f"🌐 Domain   : {result['domain']}\n")
                f.write(f"📍 IP       : {result['ip']}\n")
                f.write(f"🏳️ Ülke     : {result['country']}\n")
                f.write(f"🖥️ Sunucu   : {result['server']}\n")
                f.write(f"📊 Durum    : {result['status']}\n")
                title = result['title'][:60] + '...' if len(result['title']) > 60 else result['title']
                f.write(f"📝 Başlık   : {title}\n")
                f.write(f"🕐 Tarama   : {result['scan_time']}\n")
                f.write(f"🔍 Detay    : {result['result_url']}\n")
                f.write(f"📸 Ekran    : {result['screenshot']}\n\n")
        
        return True
    
    def save_csv(self, filename):
        """CSV olarak kaydet"""
        if not self.all_results:
            return False
        
        with open(filename, 'w', encoding='utf-8') as f:
            f.write("No,URL,Domain,IP,Country,Server,Status,Title,Scan_Time,Result_URL\n")
            
            for i, r in enumerate(self.all_results, 1):
                title = r['title'].replace('"', '""').replace('\n', ' ')
                url = r['url'].replace('"', '""')
                f.write(f'{i},"{url}","{r["domain"]}","{r["ip"]}",')
                f.write(f'"{r["country"]}","{r["server"]}","{r["status"]}",')
                f.write(f'"{title}","{r["scan_time"]}","{r["result_url"]}"\n')
        
        return True
    
    def save_json(self, filename):
        """JSON olarak kaydet"""
        if not self.all_results:
            return False
        
        export_data = {
            'meta': {
                'generated': datetime.now().isoformat(),
                'total_results': len(self.all_results),
                'tool': 'URLScan.io Search Tool - Educational Version'
            },
            'results': self.all_results
        }
        
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(export_data, f, ensure_ascii=False, indent=2)
        
        return True
    
    def get_statistics(self):
        """İstatistikleri hesapla"""
        if not self.all_results:
            return {}
        
        countries = {}
        servers = {}
        statuses = {}
        domains = set()
        
        for r in self.all_results:
            # Ülkeler
            country = r['country'] or 'Bilinmiyor'
            countries[country] = countries.get(country, 0) + 1
            
            # Sunucular
            server = (r['server'] or 'Bilinmiyor')[:20]
            servers[server] = servers.get(server, 0) + 1
            
            # HTTP durumları
            status = str(r['status']) or 'Bilinmiyor'
            statuses[status] = statuses.get(status, 0) + 1
            
            # Benzersiz domainler
            if r['domain']:
                domains.add(r['domain'])
        
        return {
            'total': len(self.all_results),
            'countries': dict(sorted(countries.items(), key=lambda x: x[1], reverse=True)),
            'servers': dict(sorted(servers.items(), key=lambda x: x[1], reverse=True)),
            'statuses': dict(sorted(statuses.items(), key=lambda x: x[1], reverse=True)),
            'unique_domains': len(domains),
            'unique_countries': len(countries)
        }


# ═══════════════════════════════════════════════════════════════════════════════
# TERMINAL (CLI) MODU
# ═══════════════════════════════════════════════════════════════════════════════

class TerminalMode:
    """Terminal arayüzü"""
    
    def __init__(self):
        self.api = URLScanAPI()
    
    def banner(self):
        """Başlık banner'ı"""
        print("""
╔══════════════════════════════════════════════════════════════════╗
║                                                                  ║
║     ██╗   ██╗██████╗ ██╗     ███████╗ ██████╗ █████╗ ███╗   ██╗  ║
║     ██║   ██║██╔══██╗██║     ██╔════╝██╔════╝██╔══██╗████╗  ██║  ║
║     ██║   ██║██████╔╝██║     ███████╗██║     ███████║██╔██╗ ██║  ║
║     ██║   ██║██╔══██╗██║     ╚════██║██║     ██╔══██╗██║╚██╗██║  ║
║     ╚██████╔╝██║  ██║███████╗███████║╚██████╗██║  ██║██║ ╚████║  ║
║      ╚═════╝ ╚═╝  ╚═╝╚══════╝╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝  ╚═══╝  ║
║                                                                  ║
║          🔍 URLScan.io Arama Aracı - Eğitim Versiyonu 🔍         ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
        """)
        print("  📚 Bu araç eğitim ve güvenlik araştırmaları için tasarlanmıştır.")
        print("  ⚠️  Lütfen sorumlu kullanın.\n")
    
    def print_help(self):
        """Yardım"""
        print("""
╔══════════════════════════════════════════════════════════════════╗
║                      ARAMA SORGUSU ÖRNEKLERİ                     ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  📁 DOSYA ARAMALARI:                                             ║
║     filename:"kedi.jpg"          - Belirli dosya adı ara         ║
║     filename:".pdf"              - PDF dosyaları ara             ║
║     filename:".xlsx"             - Excel dosyaları ara           ║
║                                                                  ║
║  🌐 DOMAIN ARAMALARI:                                            ║
║     domain:example.com           - Belirli domain ara            ║
║     page.domain:google.com       - Sayfa domain'i ara            ║
║                                                                  ║
║  🔗 URL ARAMALARI:                                               ║
║     page.url:"login"             - URL'de "login" ara            ║
║     task.url:*admin*             - Admin içeren URL'ler          ║
║                                                                  ║
║  📊 DİĞER FİLTRELER:                                             ║
║     page.country:TR              - Ülke kodu ile ara             ║
║     page.server:nginx            - Sunucu türü ile ara           ║
║     page.status:200              - HTTP durum kodu ile ara       ║
║                                                                  ║
║  🔀 KOMBİNE ARAMALAR:                                            ║
║     filename:".pdf" AND domain:edu.tr                            ║
║     page.country:TR AND filename:".doc"                          ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
        """)
    
    def cli_callback(self, event, data):
        """Terminal callback"""
        if event == 'total':
            print(f"📊 Toplam {data} sonuç mevcut!")
        elif event == 'progress':
            print(f"  📥 Sayfa {data['page']}: Toplam {data['count']}/{data['total']} sonuç alındı")
        elif event == 'rate_limit':
            print("\n⏳ Rate limit! 30 saniye bekleniyor...")
        elif event == 'timeout':
            print("\n⚠️ Zaman aşımı! Tekrar deneniyor...")
        elif event == 'error':
            print(f"\n❌ Hata: {data}")
        elif event == 'complete':
            print(f"\n✅ Arama tamamlandı! {data} sonuç bulundu.")
    
    def show_statistics(self):
        """İstatistikleri göster"""
        stats = self.api.get_statistics()
        if not stats:
            print("\n⚠️ Gösterilecek sonuç yok!")
            return
        
        print("\n" + "=" * 60)
        print("📊 ARAMA İSTATİSTİKLERİ")
        print("=" * 60)
        
        print(f"\n📋 Toplam Sonuç: {stats['total']}")
        print(f"🌐 Benzersiz Domain: {stats['unique_domains']}")
        print(f"🌍 Benzersiz Ülke: {stats['unique_countries']}")
        
        print(f"\n🌍 Ülke Dağılımı (İlk 10):")
        for i, (country, count) in enumerate(list(stats['countries'].items())[:10]):
            bar = "█" * min(count // 5 + 1, 30)
            print(f"   {country:15} : {bar} ({count})")
        
        print(f"\n🖥️ Sunucu Dağılımı (İlk 10):")
        for server, count in list(stats['servers'].items())[:10]:
            bar = "█" * min(count // 5 + 1, 30)
            print(f"   {server:20} : {bar} ({count})")
        
        print(f"\n📈 HTTP Durum Kodları:")
        for status, count in stats['statuses'].items():
            bar = "█" * min(count // 5 + 1, 30)
            print(f"   HTTP {status:10} : {bar} ({count})")
        
        print("\n" + "=" * 60)
    
    def run(self):
        """Terminal modunu çalıştır"""
        self.banner()
        
        while True:
            print("\n" + "─" * 60)
            print("📋 MENÜ")
            print("─" * 60)
            print("  1️⃣  Arama yap")
            print("  2️⃣  Yardım / Örnek sorgular")
            print("  3️⃣  Son sonuçları kaydet")
            print("  4️⃣  İstatistikleri göster")
            print("  5️⃣  GUI moduna geç")
            print("  0️⃣  Çıkış")
            print("─" * 60)
            
            choice = input("\n👉 Seçiminiz: ").strip()
            
            if choice == '0':
                print("\n👋 Güle güle!")
                break
            
            elif choice == '1':
                query = input("\n🔍 Arama sorgusu: ").strip()
                if not query:
                    print("⚠️ Boş sorgu!")
                    continue
                
                try:
                    max_results = input("📊 Maksimum sonuç (varsayılan 500): ").strip()
                    max_results = int(max_results) if max_results else 500
                    max_results = min(max_results, 50000)
                except ValueError:
                    max_results = 500
                
                print(f"\n🔍 Aranıyor: {query}")
                print(f"📊 Maksimum: {max_results}")
                print("-" * 60)
                
                self.api.search(query, max_results, self.cli_callback)
                
                if self.api.all_results:
                    save = input("\n💾 Kaydetmek ister misiniz? (e/h): ").strip().lower()
                    if save == 'e':
                        self.save_menu()
            
            elif choice == '2':
                self.print_help()
            
            elif choice == '3':
                if self.api.all_results:
                    self.save_menu()
                else:
                    print("\n⚠️ Önce arama yapın!")
            
            elif choice == '4':
                self.show_statistics()
            
            elif choice == '5':
                if GUI_AVAILABLE:
                    print("\n🖥️ GUI modu başlatılıyor...")
                    gui = GUIMode()
                    gui.run()
                else:
                    print("\n❌ Tkinter yüklü değil!")
    
    def save_menu(self):
        """Kaydetme menüsü"""
        print("\n💾 Format seçin:")
        print("  1) Sadece URL'ler (TXT)")
        print("  2) Detaylı rapor (TXT)")
        print("  3) CSV")
        print("  4) JSON")
        print("  5) Hepsi")
        
        choice = input("\n👉 Seçim: ").strip()
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        base = f"urlscan_{timestamp}"
        
        if choice == '1':
            self.api.save_txt(f"{base}_urls.txt")
            print(f"✅ Kaydedildi: {base}_urls.txt")
        elif choice == '2':
            self.api.save_detailed_txt(f"{base}_detailed.txt")
            print(f"✅ Kaydedildi: {base}_detailed.txt")
        elif choice == '3':
            self.api.save_csv(f"{base}.csv")
            print(f"✅ Kaydedildi: {base}.csv")
        elif choice == '4':
            self.api.save_json(f"{base}.json")
            print(f"✅ Kaydedildi: {base}.json")
        elif choice == '5':
            self.api.save_txt(f"{base}_urls.txt")
            self.api.save_detailed_txt(f"{base}_detailed.txt")
            self.api.save_csv(f"{base}.csv")
            self.api.save_json(f"{base}.json")
            print(f"✅ Tüm dosyalar kaydedildi!")


# ═══════════════════════════════════════════════════════════════════════════════
# GUI MODU
# ═══════════════════════════════════════════════════════════════════════════════

class ModernStyle:
    """Modern tema renkleri"""
    BG_DARK = "#1a1a2e"
    BG_MEDIUM = "#16213e"
    BG_LIGHT = "#0f3460"
    ACCENT = "#e94560"
    ACCENT_HOVER = "#ff6b6b"
    TEXT_PRIMARY = "#ffffff"
    TEXT_SECONDARY = "#a0a0a0"
    SUCCESS = "#00d26a"
    WARNING = "#ffc107"
    ERROR = "#dc3545"


class GUIMode:
    """GUI arayüzü"""
    
    def __init__(self):
        if not GUI_AVAILABLE:
            raise ImportError("Tkinter yüklü değil!")
        
        self.root = tk.Tk()
        self.root.title("🔍 URLScan.io Arama Aracı")
        self.root.geometry("1200x800")
        self.root.minsize(1000, 700)
        
        self.colors = ModernStyle()
        self.api = URLScanAPI()
        self.search_thread = None
        
        self.setup_styles()
        self.create_gui()
    
    def setup_styles(self):
        """TTK stillerini ayarla"""
        self.style = ttk.Style()
        self.style.theme_use('clam')
        
        self.style.configure("Treeview",
            background=self.colors.BG_MEDIUM,
            foreground=self.colors.TEXT_PRIMARY,
            fieldbackground=self.colors.BG_MEDIUM,
            rowheight=28
        )
        self.style.configure("Treeview.Heading",
            background=self.colors.ACCENT,
            foreground="white",
            font=('Segoe UI', 10, 'bold')
        )
        self.style.map("Treeview",
            background=[('selected', self.colors.ACCENT)]
        )
        self.style.configure("Custom.Horizontal.TProgressbar",
            background=self.colors.ACCENT,
            troughcolor=self.colors.BG_MEDIUM
        )
    
    def create_gui(self):
        """Arayüzü oluştur"""
        self.root.configure(bg=self.colors.BG_DARK)
        
        # Ana frame
        main = tk.Frame(self.root, bg=self.colors.BG_DARK)
        main.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        # === BAŞLIK ===
        header = tk.Frame(main, bg=self.colors.BG_MEDIUM, height=70)
        header.pack(fill=tk.X, pady=(0, 10))
        header.pack_propagate(False)
        
        tk.Label(header,
            text="🔍 URLScan.io Arama Aracı",
            font=('Segoe UI', 18, 'bold'),
            fg=self.colors.TEXT_PRIMARY,
            bg=self.colors.BG_MEDIUM
        ).pack(side=tk.LEFT, padx=20, pady=15)
        
        tk.Button(header,
            text="❓ Yardım",
            font=('Segoe UI', 10),
            bg=self.colors.BG_LIGHT,
            fg=self.colors.TEXT_PRIMARY,
            border=0, padx=15, pady=8,
            cursor='hand2',
            command=self.show_help
        ).pack(side=tk.RIGHT, padx=20, pady=15)
        
        # === ARAMA PANELİ ===
        search_frame = tk.Frame(main, bg=self.colors.BG_MEDIUM)
        search_frame.pack(fill=tk.X, pady=(0, 10))
        
        inner = tk.Frame(search_frame, bg=self.colors.BG_MEDIUM)
        inner.pack(fill=tk.X, padx=20, pady=15)
        
        tk.Label(inner,
            text="🔎 Arama Sorgusu:",
            font=('Segoe UI', 11, 'bold'),
            fg=self.colors.TEXT_PRIMARY,
            bg=self.colors.BG_MEDIUM
        ).pack(anchor='w', pady=(0, 5))
        
        # Arama satırı
        row = tk.Frame(inner, bg=self.colors.BG_MEDIUM)
        row.pack(fill=tk.X)
        
        self.search_entry = tk.Entry(row,
            font=('Consolas', 12),
            bg=self.colors.BG_LIGHT,
            fg=self.colors.TEXT_PRIMARY,
            insertbackground=self.colors.ACCENT,
            relief='flat'
        )
        self.search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=10, padx=(0, 10))
        self.search_entry.insert(0, 'filename:"example.jpg"')
        self.search_entry.bind('<Return>', lambda e: self.start_search())
        
        # Max sonuç
        tk.Label(row, text="Maks:", font=('Segoe UI', 10),
            fg=self.colors.TEXT_SECONDARY, bg=self.colors.BG_MEDIUM
        ).pack(side=tk.LEFT, padx=(0, 5))
        
        self.max_var = tk.StringVar(value="1000")
        tk.Entry(row,
            textvariable=self.max_var,
            font=('Segoe UI', 11),
            bg=self.colors.BG_LIGHT,
            fg=self.colors.TEXT_PRIMARY,
            relief='flat', width=8, justify='center'
        ).pack(side=tk.LEFT, ipady=8, padx=(0, 10))
        
        # Butonlar
        self.search_btn = tk.Button(row,
            text="🚀 ARA",
            font=('Segoe UI', 11, 'bold'),
            bg=self.colors.ACCENT,
            fg='white',
            border=0, padx=25, pady=10,
            cursor='hand2',
            command=self.start_search
        )
        self.search_btn.pack(side=tk.LEFT, padx=(0, 5))
        
        self.stop_btn = tk.Button(row,
            text="⏹ DUR",
            font=('Segoe UI', 11, 'bold'),
            bg=self.colors.ERROR,
            fg='white',
            border=0, padx=20, pady=10,
            cursor='hand2',
            command=self.stop_search,
            state='disabled'
        )
        self.stop_btn.pack(side=tk.LEFT)
        
        # Progress
        self.progress = ttk.Progressbar(inner,
            style="Custom.Horizontal.TProgressbar",
            mode='indeterminate'
        )
        self.progress.pack(fill=tk.X, pady=(15, 0))
        
        # Örnekler
        ex_frame = tk.Frame(inner, bg=self.colors.BG_MEDIUM)
        ex_frame.pack(fill=tk.X, pady=(10, 0))
        
        tk.Label(ex_frame, text="Örnekler:", font=('Segoe UI', 9),
            fg=self.colors.TEXT_SECONDARY, bg=self.colors.BG_MEDIUM
        ).pack(side=tk.LEFT, padx=(0, 10))
        
        for query, label in [('filename:".pdf"', 'PDF'), ('domain:edu.tr', 'Eğitim'),
                             ('page.country:TR', 'Türkiye'), ('filename:".xlsx"', 'Excel')]:
            tk.Button(ex_frame, text=label, font=('Segoe UI', 9),
                bg=self.colors.BG_LIGHT, fg=self.colors.TEXT_SECONDARY,
                border=0, padx=10, pady=3, cursor='hand2',
                command=lambda q=query: self.set_query(q)
            ).pack(side=tk.LEFT, padx=2)
        
        # === SONUÇLAR ===
        results_frame = tk.Frame(main, bg=self.colors.BG_MEDIUM)
        results_frame.pack(fill=tk.BOTH, expand=True, pady=(0, 10))
        
        # Başlık ve export
        rh = tk.Frame(results_frame, bg=self.colors.BG_MEDIUM)
        rh.pack(fill=tk.X, padx=15, pady=10)
        
        self.results_label = tk.Label(rh,
            text="📋 Sonuçlar (0)",
            font=('Segoe UI', 12, 'bold'),
            fg=self.colors.TEXT_PRIMARY,
            bg=self.colors.BG_MEDIUM
        )
        self.results_label.pack(side=tk.LEFT)
        
        for text, cmd in [("📄 TXT", self.export_txt), ("📊 CSV", self.export_csv),
                          ("📦 JSON", self.export_json), ("📑 Hepsi", self.export_all)]:
            tk.Button(rh, text=text, font=('Segoe UI', 9),
                bg=self.colors.SUCCESS, fg='white', border=0,
                padx=12, pady=5, cursor='hand2', command=cmd
            ).pack(side=tk.RIGHT, padx=2)
        
        # Treeview
        tree_frame = tk.Frame(results_frame, bg=self.colors.BG_MEDIUM)
        tree_frame.pack(fill=tk.BOTH, expand=True, padx=15, pady=(0, 15))
        
        scroll_y = ttk.Scrollbar(tree_frame)
        scroll_y.pack(side=tk.RIGHT, fill=tk.Y)
        
        scroll_x = ttk.Scrollbar(tree_frame, orient=tk.HORIZONTAL)
        scroll_x.pack(side=tk.BOTTOM, fill=tk.X)
        
        columns = ('no', 'url', 'domain', 'ip', 'country', 'status')
        self.tree = ttk.Treeview(tree_frame, columns=columns, show='headings',
            yscrollcommand=scroll_y.set, xscrollcommand=scroll_x.set)
        
        for col, text, width in [('no', '#', 50), ('url', 'URL', 400),
            ('domain', 'Domain', 200), ('ip', 'IP', 120),
            ('country', 'Ülke', 60), ('status', 'Durum', 60)]:
            self.tree.heading(col, text=text)
            self.tree.column(col, width=width, minwidth=50)
        
        self.tree.pack(fill=tk.BOTH, expand=True)
        scroll_y.config(command=self.tree.yview)
        scroll_x.config(command=self.tree.xview)
        
        # Sağ tık
        self.context_menu = tk.Menu(self.root, tearoff=0)
        self.context_menu.add_command(label="🔗 URL Kopyala", command=self.copy_url)
        self.context_menu.add_command(label="🌐 Tarayıcıda Aç", command=self.open_browser)
        self.tree.bind('<Button-3>', self.show_context)
        self.tree.bind('<Double-1>', lambda e: self.open_browser())
        
        # === İSTATİSTİK ===
        stats_frame = tk.Frame(main, bg=self.colors.BG_MEDIUM, height=100)
        stats_frame.pack(fill=tk.X, pady=(0, 10))
        stats_frame.pack_propagate(False)
        
        tk.Label(stats_frame, text="📊 İstatistikler",
            font=('Segoe UI', 11, 'bold'),
            fg=self.colors.TEXT_PRIMARY, bg=self.colors.BG_MEDIUM
        ).pack(anchor='w', padx=15, pady=(10, 5))
        
        cards = tk.Frame(stats_frame, bg=self.colors.BG_MEDIUM)
        cards.pack(fill=tk.BOTH, expand=True, padx=15, pady=(0, 10))
        
        self.stat_labels = {}
        for key, title in [('total', '📋 Toplam'), ('countries', '🌍 Ülke'),
            ('domains', '🌐 Domain'), ('success', '✅ 2xx'), ('error', '❌ 4xx/5xx')]:
            card = tk.Frame(cards, bg=self.colors.BG_LIGHT, padx=15, pady=8)
            card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=3)
            
            tk.Label(card, text=title, font=('Segoe UI', 9),
                fg=self.colors.TEXT_SECONDARY, bg=self.colors.BG_LIGHT
            ).pack(anchor='w')
            
            self.stat_labels[key] = tk.Label(card, text="0",
                font=('Segoe UI', 16, 'bold'),
                fg=self.colors.ACCENT, bg=self.colors.BG_LIGHT
            )
            self.stat_labels[key].pack(anchor='w')
        
        # === DURUM ===
        status = tk.Frame(main, bg=self.colors.BG_LIGHT, height=30)
        status.pack(fill=tk.X)
        status.pack_propagate(False)
        
        self.status_label = tk.Label(status, text="✨ Hazır",
            font=('Segoe UI', 9), fg=self.colors.TEXT_SECONDARY,
            bg=self.colors.BG_LIGHT, anchor='w'
        )
        self.status_label.pack(side=tk.LEFT, padx=10, pady=5)
    
    def set_query(self, query):
        self.search_entry.delete(0, tk.END)
        self.search_entry.insert(0, query)
    
    def set_status(self, msg, color=None):
        self.status_label.config(text=msg)
        if color:
            self.status_label.config(fg=color)
    
    def update_stats(self):
        stats = self.api.get_statistics()
        if not stats:
            return
        
        self.stat_labels['total'].config(text=str(stats['total']))
        self.stat_labels['countries'].config(text=str(stats['unique_countries']))
        self.stat_labels['domains'].config(text=str(stats['unique_domains']))
        
        success = sum(v for k, v in stats['statuses'].items() if k.startswith('2'))
        error = sum(v for k, v in stats['statuses'].items() if k.startswith(('4', '5')))
        
        self.stat_labels['success'].config(text=str(success))
        self.stat_labels['error'].config(text=str(error))
    
    def gui_callback(self, event, data):
        """GUI callback"""
        if event == 'total':
            self.root.after(0, lambda: self.set_status(
                f"📊 Toplam {data} sonuç bulundu!", self.colors.WARNING))
        elif event == 'progress':
            self.root.after(0, lambda: self.set_status(
                f"📥 Sayfa {data['page']}: {data['count']}/{data['total']}",
                self.colors.WARNING))
            self.root.after(0, lambda: self.results_label.config(
                text=f"📋 Sonuçlar ({data['count']})"))
            
            # Sonuçları ekle
            for r in data.get('results', []):
                idx = len(self.api.all_results)
                self.root.after(0, lambda r=r, i=idx: self.tree.insert('', 'end', values=(
                    i, r.get('page', {}).get('url', '')[:80],
                    r.get('page', {}).get('domain', ''),
                    r.get('page', {}).get('ip', ''),
                    r.get('page', {}).get('country', ''),
                    r.get('page', {}).get('status', '')
                )))
            
            self.root.after(0, self.update_stats)
        elif event == 'rate_limit':
            self.root.after(0, lambda: self.set_status(
                "⏳ Rate limit! Bekleniyor...", self.colors.WARNING))
        elif event == 'error':
            self.root.after(0, lambda: self.set_status(
                f"❌ Hata: {data}", self.colors.ERROR))
        elif event == 'complete':
            self.root.after(0, self.search_complete)
    
    def start_search(self):
        query = self.search_entry.get().strip()
        if not query or query == 'filename:"example.jpg"':
            messagebox.showwarning("Uyarı", "Arama sorgusu girin!")
            return
        
        try:
            max_results = int(self.max_var.get())
            max_results = min(max_results, 50000)
        except:
            max_results = 1000
        
        self.tree.delete(*self.tree.get_children())
        self.api.all_results = []
        
        self.search_btn.config(state='disabled')
        self.stop_btn.config(state='normal')
        self.progress.start(10)
        self.set_status(f"🔍 Aranıyor: {query}", self.colors.WARNING)
        
        self.search_thread = threading.Thread(
            target=self.api.search,
            args=(query, max_results, self.gui_callback)
        )
        self.search_thread.daemon = True
        self.search_thread.start()
    
    def search_complete(self):
        self.search_btn.config(state='normal')
        self.stop_btn.config(state='disabled')
        self.progress.stop()
        
        count = len(self.api.all_results)
        self.set_status(f"✅ Tamamlandı! {count} sonuç", self.colors.SUCCESS)
        self.results_label.config(text=f"📋 Sonuçlar ({count})")
        self.update_stats()
        
        if count > 0:
            messagebox.showinfo("Tamamlandı", f"🎉 {count} sonuç bulundu!")
    
    def stop_search(self):
        self.api.stop()
        self.set_status("⏹ Durduruldu", self.colors.WARNING)
    
    def show_context(self, event):
        item = self.tree.identify_row(event.y)
        if item:
            self.tree.selection_set(item)
            self.context_menu.post(event.x_root, event.y_root)
    
    def get_selected(self):
        sel = self.tree.selection()
        if not sel:
            return None
        idx = int(self.tree.item(sel[0])['values'][0]) - 1
        if 0 <= idx < len(self.api.all_results):
            return self.api.all_results[idx]
        return None
    
    def copy_url(self):
        r = self.get_selected()
        if r:
            self.root.clipboard_clear()
            self.root.clipboard_append(r['url'])
            self.set_status("📋 Kopyalandı!", self.colors.SUCCESS)
    
    def open_browser(self):
        r = self.get_selected()
        if r and r['url']:
            webbrowser.open(r['url'])
    
    def export_txt(self):
        if not self.api.all_results:
            messagebox.showwarning("Uyarı", "Sonuç yok!")
            return
        f = filedialog.asksaveasfilename(defaultextension=".txt",
            initialfilename=f"urlscan_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt")
        if f:
            self.api.save_txt(f)
            messagebox.showinfo("Başarılı", f"Kaydedildi:\n{f}")
    
    def export_csv(self):
        if not self.api.all_results:
            messagebox.showwarning("Uyarı", "Sonuç yok!")
            return
        f = filedialog.asksaveasfilename(defaultextension=".csv",
            initialfilename=f"urlscan_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv")
        if f:
            self.api.save_csv(f)
            messagebox.showinfo("Başarılı", f"Kaydedildi:\n{f}")
    
    def export_json(self):
        if not self.api.all_results:
            messagebox.showwarning("Uyarı", "Sonuç yok!")
            return
        f = filedialog.asksaveasfilename(defaultextension=".json",
            initialfilename=f"urlscan_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
        if f:
            self.api.save_json(f)
            messagebox.showinfo("Başarılı", f"Kaydedildi:\n{f}")
    
    def export_all(self):
        if not self.api.all_results:
            messagebox.showwarning("Uyarı", "Sonuç yok!")
            return
        folder = filedialog.askdirectory(title="Klasör Seçin")
        if folder:
            ts = datetime.now().strftime('%Y%m%d_%H%M%S')
            base = os.path.join(folder, f"urlscan_{ts}")
            self.api.save_txt(f"{base}_urls.txt")
            self.api.save_csv(f"{base}.csv")
            self.api.save_json(f"{base}.json")
            messagebox.showinfo("Başarılı", f"Tüm dosyalar kaydedildi!")
    
    def show_help(self):
        help_win = tk.Toplevel(self.root)
        help_win.title("Yardım")
        help_win.geometry("550x450")
        help_win.configure(bg=self.colors.BG_DARK)
        
        text = scrolledtext.ScrolledText(help_win,
            font=('Consolas', 10),
            bg=self.colors.BG_MEDIUM,
            fg=self.colors.TEXT_PRIMARY
        )
        text.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        text.insert('1.0', """
═══════════════════════════════════════════════════════
                  ARAMA SORGUSU ÖRNEKLERİ
═══════════════════════════════════════════════════════

📁 DOSYA ARAMALARI:
   filename:"kedi.jpg"     → Belirli dosya
   filename:".pdf"         → PDF dosyaları
   filename:".xlsx"        → Excel dosyaları

🌐 DOMAIN ARAMALARI:
   domain:example.com      → Domain ara
   domain:*.edu.tr         → Eğitim siteleri

🔗 URL ARAMALARI:
   page.url:"login"        → URL'de ara
   task.url:*admin*        → Admin URL'leri

📊 FİLTRELER:
   page.country:TR         → Türkiye
   page.country:US         → ABD
   page.server:nginx       → Nginx sunucular
   page.status:200         → Başarılı sayfalar

🔀 KOMBİNE:
   filename:".pdf" AND domain:edu.tr
   page.country:TR AND filename:".doc"

═══════════════════════════════════════════════════════
        """)
        text.config(state='disabled')
    
    def run(self):
        self.root.mainloop()


# ═══════════════════════════════════════════════════════════════════════════════
# ANA GİRİŞ NOKTASI
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    if '--cli' in sys.argv or '-c' in sys.argv:
        # Terminal modu
        cli = TerminalMode()
        cli.run()
    elif '--help' in sys.argv or '-h' in sys.argv:
        print("""
URLScan.io Arama Aracı
======================

Kullanım:
    python urlscan_tool.py          GUI modu (varsayılan)
    python urlscan_tool.py --cli    Terminal modu
    python urlscan_tool.py --help   Bu yardım mesajı

Özellikler:
    • 10.000+ sonuç toplama (sayfalama desteği)
    • TXT, CSV, JSON çıktı formatları
    • Detaylı istatistikler
    • Modern GUI arayüzü
        """)
    else:
        # GUI modu
        if GUI_AVAILABLE:
            gui = GUIMode()
            gui.run()
        else:
            print("❌ Tkinter yüklü değil! Terminal modu kullanılıyor...")
            cli = TerminalMode()
            cli.run()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n👋 Çıkış yapıldı.")
        sys.exit(0)