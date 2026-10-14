#ifndef SANISIZER_CAST_HPP
#define SANISIZER_CAST_HPP

#include "attest.hpp"

/**
 * @file cast.hpp
 * @brief Safe casts of integer size.
 */

namespace sanisizer {

/**
 * Check that a non-negative integer can be cast to a destination type, typically the size type of a C-style array or STL container. 
 * This is useful for chaining together checks without actually doing the cast itself.
 * 
 * @tparam Dest_ Integer type of the destination.
 * @tparam Value_ Integer type of the input value.
 * This may also be an `Attestation`.
 *
 * @param x Non-negative value to be casted.
 *
 * @return `x` as its input type (or the corresponding integer type, if it was an `Attestation`).
 * An error is thrown if overflow would occur.
 */
template<typename Size_, typename Value_>
constexpr auto can_cast(Value_ x) {
    check_overflow<Size_>(x);
    return get_value(x);
}

/**
 * Cast a non-negative integer to a destination type, typically the size type of a C-style array or STL container.
 * This avoids accidental overflow from an implicit cast when `x` is used in `new` or the container's constructor.
 * 
 * @tparam Dest_ Integer type of the destination.
 * @tparam Value_ Integer type of the input value.
 * This may also be an `Attestation`.
 *
 * @param x Non-negative value to be casted.
 *
 * @return `x` as a `Dest_`.
 * An error is thrown if overflow would occur.
 */
template<typename Dest_, typename Value_>
constexpr Dest_ cast(Value_ x) {
    return can_cast<Dest_>(x);
}

}

#endif
