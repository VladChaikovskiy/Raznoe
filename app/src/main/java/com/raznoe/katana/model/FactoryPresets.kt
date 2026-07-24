package com.raznoe.katana.model

/**
 * Built-in "factory" presets — MY OWN starting-point tones, crafted in the
 * spirit of the well-known Katana Librarian demo names (JNs …). These are
 * honest re-creations, NOT the original JNs patches (whose exact values live
 * only inside their .tsl files). Each note says so.
 *
 * Values reference [com.raznoe.katana.protocol.KatanaParams] ids and use the
 * community MkII wire values. Amp type indices: 0 Acoustic, 1 Clean, 2 Pushed,
 * 3 Crunch, 4 Lead, 5 Brown.
 */
object FactoryPresets {

    private class Builder {
        val v = LinkedHashMap<String, Int>().apply {
            // deterministic baseline: everything off unless a preset enables it
            put("boost_sw", 0); put("boost_solo", 0)
            put("mod_sw", 0); put("fx_sw", 0)
            put("delay_sw", 0); put("reverb_sw", 0); put("ns_sw", 0)
        }

        fun amp(type: Int, gain: Int, bass: Int, mid: Int, treble: Int, pres: Int, vol: Int = 80) {
            v["amp_type"] = type; v["gain"] = gain; v["volume"] = vol
            v["bass"] = bass; v["middle"] = mid; v["treble"] = treble; v["presence"] = pres
        }

        fun boost(type: Int, drive: Int, level: Int = 70, bottom: Int = 50, tone: Int = 50) {
            v["boost_sw"] = 1; v["boost_type"] = type; v["boost_drive"] = drive
            v["boost_level"] = level; v["boost_bottom"] = bottom; v["boost_tone"] = tone
        }

        fun mod(type: Int) { v["mod_sw"] = 1; v["mod_type"] = type }
        fun fx(type: Int) { v["fx_sw"] = 1; v["fx_type"] = type }

        fun delay(type: Int, time: Int, fb: Int, level: Int = 45) {
            v["delay_sw"] = 1; v["delay_type"] = type; v["delay_time"] = time
            v["delay_fb"] = fb; v["delay_level"] = level
        }

        fun reverb(type: Int, level: Int = 40, time: Int = 50) {
            v["reverb_sw"] = 1; v["reverb_type"] = type
            v["reverb_level"] = level; v["reverb_time"] = time
        }

        fun ns(thr: Int = 40) { v["ns_sw"] = 1; v["ns_thr"] = thr }
    }

    private fun preset(name: String, note: String, build: Builder.() -> Unit): Patch {
        val b = Builder(); b.build(); return Patch(name = name, values = b.v, note = note)
    }

    private const val N = "Моя версия (не оригинал JNs)"

    val ALL: List<Patch> = listOf(
        preset("AC/DC Crunch", "$N · плотный Marshall-кранч, минимум эффектов") {
            amp(3, 62, 52, 68, 58, 55); reverb(1, level = 22, time = 35)
        },
        preset("Fusion Lead", "$N · певучий лид с дилеем и холлом") {
            amp(4, 78, 45, 60, 55, 52); boost(12, 40, 60)
            delay(0, 440, 35); reverb(3, 35)
        },
        preset("Fusion Crunch", "$N · упругий кранч для риффов и аккордов") {
            amp(3, 55, 50, 58, 55, 48); delay(0, 380, 22, 28); reverb(1, 22)
        },
        preset("Gary Moore Clean", "$N · тёплый чистый с холлом и хорусом") {
            amp(1, 30, 55, 55, 55, 45); mod(29); reverb(3, level = 45, time = 60)
        },
        preset("Gary Moore Solo", "$N · жирный поющий лид") {
            amp(4, 82, 50, 70, 55, 50); boost(12, 45, 65)
            delay(7, 400, 40, 40); reverb(3, 38)
        },
        preset("Green Day Punk", "$N · яркий панк-кранч с бустом") {
            amp(3, 72, 55, 60, 62, 60); boost(3, 50, 60); ns(35)
        },
        preset("Hardwire Metal", "$N · плотный современный хай-гейн") {
            amp(5, 85, 60, 40, 60, 55); ns(42); reverb(1, 15)
        },
        preset("Metal Rhythm", "$N · тугой ритм со скупыми серединами") {
            amp(5, 88, 62, 35, 58, 55); ns(45)
        },
        preset("Metal Solo", "$N · хай-гейн лид с дилеем") {
            amp(5, 88, 55, 55, 62, 58); boost(0, 40, 60)
            delay(0, 400, 30, 35); reverb(3, 30); ns(40)
        },
        preset("Octave Fuzz", "$N · фузз с октавой сверху") {
            amp(3, 55, 55, 55, 55, 50); boost(19, 70, 55); fx(14); reverb(1, 20)
        },
        preset("Pink Floyd Lead", "$N · gilmour-style: овердрайв + большой дилей") {
            amp(4, 70, 50, 60, 52, 48); boost(10, 45, 60)
            delay(0, 440, 38, 45); reverb(3, 40)
        },
        preset("S-H Jazz", "$N · чистый джаз в духе JC-120 с хорусом") {
            amp(1, 20, 55, 50, 45, 40); mod(29); reverb(1, 30)
        },
        preset("Soft Lead", "$N · мягкий лид средней перегрузки") {
            amp(4, 60, 48, 58, 52, 48); delay(0, 380, 30, 40); reverb(3, 40)
        },
        preset("Sweet Strat", "$N · чистый спанк со стратокастера, хорус") {
            amp(1, 35, 50, 52, 58, 50); mod(29); reverb(5, 30)
        },
    )
}
