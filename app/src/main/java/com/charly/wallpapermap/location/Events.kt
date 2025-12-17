package com.charly.wallpapermap.location

// Eventos que el GPS puede reportar al controller (son *mensajes*, no acciones directas)
enum class GpsEventType {
    REAL_MOVEMENT,   // GPS detectó desplazamiento real (por encima del umbral)
    FALSE_MOVEMENT,  // GPS detectó desplazamiento menor al umbral (posible ruido)
    COOLDOWN_START,  // GPS entró en cooldown (pausa temporal por falsos positivos)
    COOLDOWN_END     // Finalizó cooldown
}

// Eventos que el acelerómetro puede reportar al controller
enum class AccelEventType {
    MOVEMENT_DETECTED, // Varianza alta -> movimiento detectado
    STABLE_DETECTED    // Varianza baja sostenida -> estable
}
