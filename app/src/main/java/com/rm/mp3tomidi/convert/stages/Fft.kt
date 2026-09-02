package com.rm.mp3tomidi.convert.stages

/** Iterative radix-2 Cooley-Tukey FFT operating in place on parallel real/imaginary arrays. */
object Fft {

    /** Transforms [re]/[im] in place (forward DFT, unnormalized). Size must be a power of two. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(re.size == im.size) { "re and im must be the same size" }
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang).toFloat()
            val wi = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until len / 2) {
                    val evenRe = re[i + k]
                    val evenIm = im[i + k]
                    val oddRe = re[i + k + len / 2]
                    val oddIm = im[i + k + len / 2]
                    val twiddledRe = oddRe * curWr - oddIm * curWi
                    val twiddledIm = oddRe * curWi + oddIm * curWr
                    re[i + k] = evenRe + twiddledRe
                    im[i + k] = evenIm + twiddledIm
                    re[i + k + len / 2] = evenRe - twiddledRe
                    im[i + k + len / 2] = evenIm - twiddledIm
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
