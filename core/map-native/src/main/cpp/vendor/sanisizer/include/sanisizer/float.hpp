#ifndef SANISIZER_FLOAT_HPP
#define SANISIZER_FLOAT_HPP

#include <limits>
#include <cmath>
#include <stdexcept>
#include <type_traits>
#include <cassert>

#include "utils.hpp"
#include "attest.hpp"

/**
 * @file float.hpp
 * @brief Safely convert floats to/from integer sizes.
 */

namespace sanisizer {

/**
 * @tparam Float_ Floating-point type.
 *
 * @param x A finite non-negative integer.
 * Specifically, a floating-point number for which the following is true: `std::isfinite(x)`, `x >= 0` and `x == std::trunc(x)`.
 *
 * @return Number of bits required to store `x` exactly in a (two's complement) integer type.
 *
 * The return value can be compared to `std::numeric_limits<Integer_>::digits` to determine if `Integer_` is large enough to store `x`.
 */
template<typename Float_>
int required_bits_for_float(Float_ x) {
    static_assert(std::is_floating_point<Float_>::value);
    assert(x >= 0);
    assert(std::isfinite(x));
    assert(std::trunc(x) == x);

#ifndef SANISIZER_FLOAT_FORCE_FREXP
    if constexpr(std::numeric_limits<Float_>::radix == 2) {
        if (x == 0) {
            return 0;
        } else {
            // ilogb returns an 'exp' such that 2^exp <= x < 2^(exp + 1).
            return std::ilogb(x) + 1;
        }
    } else {
#endif
        // Ensure we're covered for weird float types where the radix is not 2.
        int exp;
        std::frexp(x, &exp); 
        // frexp guarantees that 2^(exp - 1) <= x < 2^exp.
        return exp;
#ifndef SANISIZER_FLOAT_FORCE_FREXP
    }
#endif
}

/**
 * Safely convert a non-negative floating-point number to an integer with truncation.
 * This is occasionally necessary when the size of a container or number of loop iterations is determined by floating-point calculations.
 *
 * @tparam Integer_ Integer type.
 * @tparam Float_ Floating-point type.
 *
 * @param x Floating-point number, usually holding some kind of size. 
 *
 * @return The value of `x` as an integer, after truncation.
 * An exception is raised if `x` is negative, non-finite or overflow would occur.
 */
template<typename Integer_, typename Float_>
Integer_ from_float(Float_ x) {
    static_assert(std::is_floating_point<Float_>::value);
    static_assert(std::is_integral<Integer_>::value);

    if (!std::isfinite(x)) {
        throw std::range_error("invalid conversion of non-finite value in sanisizer::from_float");
    }
    if (x < 0) {
        throw std::out_of_range("negative input value in sanisizer::from_float");
    }
    x = std::trunc(x);

    constexpr auto output_precision = std::numeric_limits<Integer_>::digits;
    if (required_bits_for_float(x) > output_precision) {
        throw std::overflow_error("overflow detected in sanisizer::from_float");
    }

    return x;
}

/**
 * Safely convert a non-negative integer into a floating-point number without loss of precision.
 * This is occasionally necessary when the surrounding environment does not have a dedicated integer type (e.g., Javascript).
 *
 * Note that the "safety" of this function is based on preserving precision rather than avoiding undefined behavior.
 * If the implementation's floats are compliant with the IEEE-754 specifiation, very large integers will already be safely converted to positive infinity via regular casts.
 *
 * @tparam Integer_ Integer type.
 * This can also be an `Attestation`.
 * @tparam Float_ Floating-point type.
 * This should be one of the IEEE-754 binary formats.
 *
 * @param x Non-negative integer, usually holding some kind of size. 
 *
 * @return The value of `x` as a floating-point number.
 * An exception is raised if overflow would occur.
 */
template<typename Float_, typename Integer_>
Float_ to_float(Integer_ x) {
    const auto val = get_value(x);

    // protect against the various -1 operations.
    constexpr auto xmax = get_max<I<decltype(x)> >();
    if constexpr(xmax == 0) {
        return 0;
    } else if (val == 0) {
        return 0;
    }

    constexpr auto frad = std::numeric_limits<Float_>::radix;
    constexpr auto fdig = std::numeric_limits<Float_>::digits;

    // Only considering IEEE-754 binary formats, to keep things simple.
    // Also I don't even have the hardware to test the other formats.
    static_assert(frad == 2);
    static_assert(std::numeric_limits<Float_>::is_iec559);

    // Checking digits first, because attempting to right-shift an integer by a number equal to or more than its bits is UB.
    if constexpr(std::numeric_limits<I<decltype(val)> >::digits > fdig) {
        // We can represent '2^fdig' itself due to the implicit leading bit on the mantissa in a (normal) IEEE binary float.
        // Thus, we subtract 1 to avoid throwing an error when 'x == 2^fdig'.
        if constexpr((xmax - 1) >> fdig) {
            const auto y = (val - 1) >> fdig;
            if (y) {
                throw std::overflow_error("overflow detected in sanisizer::to_float");
            }
        }
    }

    return val;
}

}

#endif
