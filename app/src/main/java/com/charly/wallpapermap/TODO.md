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
<<<<<<< HEAD
=======

1. Pre-fetching y Conversión (WebP + Caché)

Si vas a descargar PNGs para después convertirlos, estás haciendo laburo doble en el dispositivo. Lo ideal sería que tu Worker de descarga detecte si el tile ya está en el sistema de archivos.

    El flujo: Descarga PNG -> Decodificación -> Encoding a WebP (con compresión 75-80%) -> Almacenamiento.

    La ventaja: El WebP no solo ocupa menos espacio en el disco, sino que el tiempo de lectura y subida a RAM es menor (menos bytes que mover por el bus).

2. Texture Atlasing (El "Santo Grial" de las Draw Calls)

Tirar 300 draw calls es como ir al supermercado y hacer una cola distinta para cada producto que compraste. Una estupidez.

La idea es crear una Textura Maestra (un Canvas en memoria de, por ejemplo, 2048x2048) y "pegar" los tiles chiquitos ahí adentro.

    En lugar de pasarle a la GPU 300 IDs de texturas, le pasás uno.

    Lo único que cambiás en cada cuadrado del mapa son las coordenadas UV (qué pedacito de la textura grande tiene que dibujar en ese cuadrado).

    Resultado: El procesador le da una sola orden a la GPU y ella se encarga del resto. El alivio en el CPU es inmediato.

3. Paralelismo: Más allá de los dos hilos

Tener solo dos hilos (Location y "Todo lo demás") es lo que te está matando el renderizado. "Todo lo demás" es una bolsa de gatos donde la UI pelea con la decodificación de imágenes.

Deberías separar los tantos así:

    Main Thread (UI): Solo para el renderizado final y la entrada del usuario. Nada de lógica pesada.

    Location Thread: Lo tenés perfecto, que siga ahí tranquilo.

    IO Thread Pool: Un grupo de hilos para leer del disco y descargar de la red.

    Decoding/Processing Thread: Un hilo (o pool) dedicado exclusivamente a transformar esos bytes de WebP en mapas de bits listos para la GPU.

4. Hardware Acceleration
En Android, asegurate de que tu Surface o Canvas esté usando Hardware Acceleration. 
Si estás dibujando a mano en un onDraw, estás usando la CPU para calcular píxeles. 
Lo ideal para lo que querés hacer es usar OpenGL ES o Vulkan (si te sentís valiente), o al menos un TextureView que permita 
que la GPU maneje el buffer de dibujo directamente.


Para el prefetch
Radio_Carga=Radio_Base+(Velocidad_Actual×Factor_Anticipacion)



GEOFENCING:

1. El modo "Vacaciones" (Geofence en casa)

Podés registrar una ubicación fija (tu casa) con un radio de, supongamos, 50 metros.

    Evento GEOFENCE_TRANSITION_ENTER: En cuanto cruzás la puerta, tu BroadcastReceiver recibe la señal, matás el FusedLocationProvider y la app queda en consumo cero. Solo queda un "centinela" a nivel de sistema operativo esperando que salgas.

    Evento GEOFENCE_TRANSITION_EXIT: Cuando salís a la calle, el SO te despierta la app ("¡Che, se fue de las casas!"), volvés a activar el GPS y empezás a trackear.

2. Geofencing Dinámico (El "escudo" móvil)

Esto es lo más técnico y lo que mejor te va a solucionar lo de la batería:

    Cada vez que el GPS detecta que la velocidad es casi cero por más de un minuto, generás un Geofence temporal alrededor de esa posición actual (un radio de 20 o 30 metros).

    Apagás el GPS activo.

    Si el usuario se mueve y sale de ese "escudo", repetís el proceso: prendés GPS -> detectás movimiento -> si frena -> creás nuevo Geofence.

3. Modo Manual para el usuario

Para el usuario entusiasta, podés dejar que dibuje círculos en el mapa.

    "Zona de trabajo": Radio 100m -> Actualización cada 30s (modo chill).

    "Ruta/Carretera": Sin Geofence -> Actualización cada 1s (modo active).

    "Casa": Radio 50m -> App Off.



Tecnicas para Geofencing

1. La Máquina de Estados: "Active" vs "Sleeping"

No podés confiar solo en el evento del Geofence; necesitás una lógica que gestione la transición para que el GPS no se tilde.

    Estado ACTIVE: GPS pidiendo a 1s (o lo que rinda).

    Estado SLEEPING: GPS OFF. Solo el Geofence activo.

El Gatillo de Dormida: No duermas la app apenas el GPS marque "velocidad 0". El GPS tiene ruido estadístico. Esperá a que el usuario esté en un radio de 10 metros por, digamos, 2 minutos. Recién ahí:

    Tomás la ubicación promedio de esos 2 minutos (para evitar el jitter).

    Clavás el Geofence dinámico (el "escudo").

    Matás el GPS.

2. El "Buffer de Calentamiento" (Warm-up Strategy)

Para evitar el "null" y los bugs cuando el usuario sale del Geofence, no podés pretender que el mapa se mueva al milisegundo.

    Salida del Círculo: El SO te avisa que el usuario salió.

    Acción: Prendés el Fused Location en modo PRIORITY_HIGH_ACCURACY.

    El Truco: Durante los primeros 5-10 segundos post-salida (mientras el GPS "calienta" y tira datos inestables), mantené la cámara del mapa quieta o con un movimiento inercial suave basado en la última velocidad conocida. No le pases el dato crudo al render hasta que la precisión (accuracy) sea menor a 15 metros.
    Este mismo truco deberemos aplicarlo a todo, para dejar de recibir logs del estilo: "[LocationManager] Location request 433624D6(Listener) gps interval=1000ms (min=0ms) from com.google.android.gms.persistent[com.charly.wallpapermap] (10274_FINE_fg_true_foreground)".
    Atribuyo ese error a que estamos pidiendo ubicaciones rápido e intentando dibujarlas sin que el GPS logre devolver nada.

3. El "Histeresis" (Para no prender/apagar al pedo)

Si ponés un Geofence de 20 metros y el usuario está caminando justo por el borde, la app va a estar prendiendo y apagando el GPS como loca.

Solución: Usá radios diferentes para entrar y salir.

    Si estás quieto, el Geofence de salida es de 30 metros.

    Una vez que saliste y el GPS está activo, no vuelvas a crear el Geofence hasta que el usuario esté quieto por lo menos 50 metros lejos del punto anterior.

    Eso crea una "zona muerta" que evita que el sistema se vuelva inestable en los límites.

4. Prioridades de Ubicación

En el modo automático, podés jugar con los niveles de prioridad de Android:

    En Geofence (Home/Work): Usás PRIORITY_NO_POWER. La app no pide nada, solo escucha si otra app pide GPS.

    Saliendo de Geofence: PRIORITY_BALANCED_POWER_ACCURACY (usa antenas y Wi-Fi para un fix rápido).

    En Movimiento: PRIORITY_HIGH_ACCURACY (GPS puro).



>>>>>>> 6a8860a (Cambio de laptop)
