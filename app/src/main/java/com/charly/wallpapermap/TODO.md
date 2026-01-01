TODO

Cache del mapa. Es necesario hacer que se cachee o renderice quizás un poco más de lo visible en el mapa. Para todos los niveles de zoom, claro.

Tambien dar la opcion de cachear zonas del mapa a gusto, con un menu y eso.

Ya mismo hay que mejorar el punto de ubicacion, que esta escalando mal dependiendo el zoom.

Mejorar los filtros y opciones para el día y la noche.

Agregar un modo "lugares de interés" para cuando la ubicación está desactivada, que muestre otros lugares "interesantes" de ver.

Agregar la opción de frecuencia de fixes al GPS. Por defecto está cada 1 segundo, pero se puede subir a 2 segundos sin sufrir demasiado en la precisión. Opciones desde 1 segundo hasta 4 segundos.

Mejorar la gestión de la batería. Quizás bajar la precisión del GPS cuando la batería está baja, o desactivar el live wallpaper.

Cuando la app no esté en primer plano, no matar el live wallpaper como se hace actualmente, pero sí bajar mucho la precisión del GPS y la frecuencia de actualización del live wallpaper,
al igual que el renderizado. Quizás actualizar el live wallpaper cada un minuto o más en ese caso. Agregar como opcional.

Mejorar la gestión de los hilos. Actualmente todo el renderizado y la lógica del live wallpaper se hace en un solo hilo, lo que puede causar problemas de rendimiento.
Separar la lógica del GPS, el renderizado y la gestión de la UI en hilos diferentes.

Agregar soporte para mapas offline. Descargar mapas para usarlos sin conexión a internet.
