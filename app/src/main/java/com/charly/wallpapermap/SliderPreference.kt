package com.charly.wallpapermap

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.slider.Slider

class SliderPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private var currentValue: Int = 13 // Valor por defecto
    private var minValue: Int = 4
    private var maxValue: Int = 21

    // Este método se llama cuando la vista de la preferencia se va a mostrar.
    // Aquí es donde conectamos nuestro layout y la lógica.
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false // Para que no se pueda hacer click en toda la fila

        val slider = holder.findViewById(R.id.slider) as Slider
        val valueTextView = holder.findViewById(R.id.slider_value) as TextView

        // Configurar los rangos del slider
        slider.valueFrom = minValue.toFloat()
        slider.valueTo = maxValue.toFloat()
        slider.value = currentValue.toFloat()

        // Mostrar el valor actual
        valueTextView.text = currentValue.toString()

        // Escuchar cambios en el slider
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intValue = value.toInt()
                // Actualizar el texto
                valueTextView.text = intValue.toString()
                // Guardar el valor
                persistValue(intValue)
            }
        }
    }

    // Método para guardar el valor persistentemente
    private fun persistValue(value: Int) {
        if (callChangeListener(value)) {
            this.currentValue = value
            persistInt(value)
            notifyChanged()
        }
    }

    // Se llama para obtener el valor inicial desde SharedPreferences
    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        currentValue = getPersistedInt(defaultValue as? Int ?: 13)
    }
}
