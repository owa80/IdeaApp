# IdeApp

App Android nativa (Kotlin + SQLite) para guardar ideas, sitios y notas como
texto, audio o foto, organizadas en categorías y subcategorías.

## Funciones

- Pantalla principal con accesos rápidos: Nueva idea (Escribir / Audio / Foto), Buscar y Categorías.
- Cada idea tiene un nombre, pertenece a una sola categoría o subcategoría y puede contener varios elementos de cualquier tipo: texto, audio, foto y vídeo.
- Dentro de una idea se pueden añadir, borrar y mover elementos a otra idea.
- Mantén pulsada una idea en las listas para moverla a otra categoría o eliminarla.
- Crear, renombrar y eliminar categorías y subcategorías (no se elimina una que tenga ítems).
- Buscar por nombre del ítem, de su categoría o subcategoría, o por el texto de sus elementos.

## Obtener el .apk sin Android Studio

1. GitHub compila la app en cada push a `main` y en cada PR.
2. Entra en la pestaña **Actions** del repositorio, abre la última ejecución y descarga el artefacto `IdeApp-apk`.
3. En las versiones publicadas en `main`, el .apk también aparece en **Releases**.
4. Copia el .apk al móvil y ábrelo. Android pedirá permiso para instalar apps desconocidas la primera vez.

El .apk se firma con una clave de depuración fija incluida en el repositorio, así que cada versión nueva se instala encima de la anterior.

## Estructura

- `app/src/main/java/com/ideapp/Db.kt`: base de datos SQLite (tablas `categories` y `items`).
- `app/src/main/java/com/ideapp/*Activity.kt`: pantallas.
- `.github/workflows/build-apk.yml`: compilación automática.
