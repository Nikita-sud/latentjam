#ifndef SANISIZER_CAP_HPP
#define SANISIZER_CAP_HPP

#include <limits>
#include <type_traits>

#include "utils.hpp"
#include "attest.hpp"

/**
 * @file cap.hpp
 * @brief Cap a value at the largest size.
 */

namespace sanisizer {

/**
 * Cap a non-negative integer at the largest value of a destination type.
 * This is primarily intended for setting appropriate default values of function arguments and class variables.
 *
 * @tparam Dest_ Integer type of the destination.
 * @tparam Value_ Integer type of the input value.
 * This may also be an `Attestation`.
 *
 * @param x Non-negative value to be capped.
 *
 * @return `x` if it can be represented in `Dest_`, otherwise the maximum value of `Dest_`.
 */
template<typename Dest_, typename Value_>
constexpr Dest_ cap(Value_ x) {
    static_assert(std::is_integral<Dest_>::value);
    constexpr auto maxed = std::numeric_limits<Dest_>::max();
    constexpr auto umaxed = as_unsigned(maxed);

    const auto val = get_value(x);
    if constexpr(umaxed >= as_unsigned(get_max<Value_>())) {
        return val;
    } else if (umaxed >= as_unsigned(val)) {
        return val;
    } else {
        return maxed;
    }
}

}

#endif
