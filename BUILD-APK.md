# Bygg installerbar FIELDLINK APK

Denne kodepakken inneholder Android-kildekoden og en GitHub Actions-workflow som bygger en **debug-APK**.

## Skybygging med GitHub

1. Opprett et privat repository på GitHub, for eksempel `fieldlink`.
2. Last opp innholdet i denne mappen (`fieldlink/`) til repository-roten. Pass på at `android/` og `.github/` ligger direkte i roten.
3. Åpne fanen **Actions** i GitHub-repositoryet.
4. Velg **Build FIELDLINK Android APK**.
5. Trykk **Run workflow**.
6. Når jobben er ferdig, åpne den fullførte kjøringen og last ned artifact `fieldlink-debug-apk`.
7. Pakk ut artifact-zip-filen. APK-filen heter `app-debug.apk`.

## Installer på DJI RC Plus

1. Overfør `app-debug.apk` til kontrolleren.
2. Åpne APK-filen i filbehandleren og tillat installasjon fra den aktuelle kilden dersom Android ber om det.
3. Installer FIELDLINK Uploader.
4. Ikke bruk den til produksjonskritiske opptak før filtilgang og overføring er testet på den aktuelle RC Plus-fastvaren.

## Viktig

Dette er en **debug/test-APK**, ikke en signert produksjonsutgave. Den krever en server med gyldig HTTPS, en prosjekt-ID og en device token. APK-en kan bygges før domenet er bestemt fordi serveradressen legges inn i appen.

Denne utviklingsversjonen er ikke verifisert på fysisk DJI RC Plus. Androids mappevelger må kunne gi appen lesetilgang til DJI CACHE_VIDEO. Det må testes på kontrolleren.
