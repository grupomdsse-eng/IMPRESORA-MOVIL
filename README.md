# MDS Oficio México · Android

Aplicación Android simplificada para clientes de Grupo MDS.

## Objetivo

El cliente no configura IPP, puertos ni tamaños de papel. El flujo normal es:

1. Instalar `MDS_Oficio_Mexico.apk`.
2. Abrir la app y pulsar **ACTIVAR MDS PRINT**.
3. En los ajustes de Android, activar **MDS Print**. Esto solo se hace una vez.
4. Volver a la app y pulsar **SELECCIONAR PDF E IMPRIMIR**, o compartir/abrir un PDF con **MDS Oficio México**.
5. La impresión se inicia con **Oficio México 216 × 340 mm** como tamaño predeterminado.
6. Seleccionar la impresora detectada por MDS Print y pulsar Imprimir.

Android normalmente recuerda la última impresora seleccionada, por lo que las siguientes impresiones requieren todavía menos pasos.

## Modo soporte técnico

La pantalla principal tiene un botón **CONFIGURACIÓN AVANZADA · SOLO SOPORTE**. Aquí se puede:

- Añadir una impresora IPP manualmente por IP.
- Probar la conexión IPP.
- Crear otros tamaños personalizados.

El cliente normal no necesita entrar aquí.

## Impresoras

La versión actual busca impresoras de red que anuncien `_ipp._tcp` mediante mDNS/NSD. También permite configurar manualmente IP, puerto 631 y ruta IPP desde el modo avanzado.

El envío del trabajo se realiza mediante IPP y, para Oficio México, se transmite el tamaño personalizado 21600 × 34000 en centésimas de milímetro mediante `media-col/media-size`.

## PDF

Cuando el PDF entra por la aplicación, `PdfFilePrintAdapter` genera un documento adaptado al tamaño seleccionado por Android. El flujo normal comienza directamente con **216 × 340 mm**.

## Compilar APK con GitHub

1. Crear un repositorio nuevo en GitHub.
2. Subir TODO el contenido de esta carpeta, incluida `.github`.
3. Entrar en **Actions**.
4. Ejecutar **Compilar APK Android**.
5. Descargar el artefacto **MDS-Oficio-Mexico-APK**.
6. Dentro estará `MDS_Oficio_Mexico.apk`. Ese es el único archivo que se entrega al cliente.

## Requisitos de compilación

> Corrección V2.0.1: se eliminó la dependencia de `platforms;android-37`. El workflow instala la plataforma estable `platforms;android-36` y Build Tools `36.0.0`.

- Java 17
- Android SDK 36 (Android 16, estable)
- compileSdk 36
- targetSdk 36
- minSdk 26 (Android 8.0+)

## Nota de Android

Android no permite que una app active silenciosamente su propio servicio de impresión. El usuario debe activar **MDS Print** una vez en los ajustes del sistema. Después, la app puede comprobar su estado en Android 13+ y guiar al usuario.

## V2.1.0 - motor IPP/PWG

Esta versión corrige el error de impresión que podía producirse al enviar siempre PDF directamente.
El motor consulta `document-format-supported`, intenta PDF cuando está disponible y usa
`image/pwg-raster` automáticamente como formato driverless alternativo. También registra un
diagnóstico de la última impresión en Configuración avanzada.
