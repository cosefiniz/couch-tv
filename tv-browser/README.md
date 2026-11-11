# TV Tarayıcı

Google TV / Android TV için kumandayla kullanılan küçük web tarayıcısı (bir Grundig 55GQ750A'da yazıldı ve denendi). Sistem WebView'ını kullanır; eski siteler
masaüstü düzeninde açılır, her şey TV kumandasıyla yapılır.

- Paket: `app.couchtv.browser` · APK: `tv-tarayici.apk` (bu klasörde)
- TV'de: uygulama satırında **TV Tarayıcı** (couch-tv ana ekranı otomatik gösterir).

## Kumanda
| Tuş | İş |
|---|---|
| Ok tuşları | İmleci taşır (basılı tutunca hızlanır). Kenara itince sayfa kayar. |
| OK | Tıklar |
| OK'a basılı tut | Kaydırma modu: oklar sayfayı kaydırır; OK veya Geri ile çık |
| Geri | Önceki sayfa (ana sayfada: uygulamadan çık) |
| İmleç en üstteyken Yukarı | Üst çubuk (geri, ileri, yenile, ana sayfa, adres, ☆, masaüstü/mobil, −/+, imleç, menü) |
| Kanal + / − | Sayfa yukarı / aşağı |
| Kırmızı / Yeşil / Sarı / Mavi | İmleç aç-kapat / Uzaklaştır / Yakınlaştır / Masaüstü-mobil |
| Menü | Menü (yazı boyu, geçmiş, indirilenler, yardım…) |

İmleç kapalıyken oklar sayfadaki bağlantılar arasında gezer (normal TV odak gezintisi).
Yer işareti kutusunda OK'a basılı tutunca: aç / başa taşı / kaldır.

## Eski siteler için yapılanlar
- Varsayılan masaüstü Chrome kimliği (Windows), site başına mobil görünüme geçilebilir (mavi tuş / üst çubuk).
- Geniş sayfa düzeni (`useWideViewPort` + `loadWithOverviewMode`); "device-width" isteyen sitelere masaüstünde 1200px genişlik verilir.
- Site başına yakınlık ve yazı boyu hatırlanır.
- Karışık içerik (https içinde http) izinli, açılır pencereler aynı sayfada açılır, `X-Requested-With` başlığı gönderilmez.
- Sertifikası geçersiz eski sitelerde "Yine de aç" sorusu çıkar.
- Adres yazarken `site.com` gibi girişler önce `http://` ile açılır; adres değilse Google araması yapılır.
- Dosya indirme (İndirilenler klasörüne), tam ekran video.

## Derleme
JDK 21 ve Android SDK gerekir; `build.cmd` bunları `COUCHTV_TOOLS` klasöründe arar (bkz. `phone-app/build.cmd`).
APK: `app/build/outputs/apk/release/app-release.apk`. İkon/banner için: `python -I scripts/make_res.py`.
`keystore/debug.keystore` varsa (git'e girmez) onunla imzalanır; TV'deki sürüm hangi anahtarla kurulduysa
güncellemeler de aynı anahtarla yapılmalı, yoksa TV "imza farklı" der.

## TV'ye kurma / güncelleme (köprü bilgisayarı üzerinden)
```sh
scp app/build/outputs/apk/release/app-release.apk <kullanıcı>@<köprü>:/tmp/tv-tarayici.apk
ssh <kullanıcı>@<köprü> "adb -s <tv-ip>:5555 install -r /tmp/tv-tarayici.apk"
```

## Kaldırma
`adb -s <tv-ip>:5555 uninstall app.couchtv.browser`, ya da TV'de: Ayarlar → Uygulamalar → TV Tarayıcı → Kaldır.
