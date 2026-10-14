#ifndef SANISIZER_COMPARISONS_HPP
#define SANISIZER_COMPARISONS_HPP

#include "utils.hpp"
#include "attest.hpp"

/**
 * @file comparisons.hpp
 * @brief Signedness-safe integer comparisons.
 */

namespace sanisizer {

/**
 * @tparam Left_ Integer type on the left hand side of the comparison.
 * This may also be an `Attestation`.
 * @tparam Right_ Integer type on the right hand side of the comparison.
 * This may also be an `Attestation`.
 *
 * @param left Non-negative value on the left hand side of the comparison.
 * @param right Non-negative value on the right hand side of the comparison.
 *
 * @return Whether `left` is equal to `right`.
 */
template<typename Left_, typename Right_>
constexpr bool is_equal(Left_ left, Right_ right) {
    return as_unsigned(get_value(left)) == as_unsigned(get_value(right));
}

/**
 * @tparam Left_ Integer type on the left hand side of the comparison.
 * This may also be an `Attestation`.
 * @tparam Right_ Integer type on the right hand side of the comparison.
 * This may also be an `Attestation`.
 *
 * @param left Non-negative value on the left hand side of the comparison.
 * @param right Non-negative value on the right hand side of the comparison.
 *
 * @return Whether `left` is less than `right`.
 */
template<typename Left_, typename Right_>
constexpr bool is_less_than(Left_ left, Right_ right) {
    return as_unsigned(get_value(left)) < as_unsigned(get_value(right));
}

/**
 * @tparam Left_ Integer type on the left hand side of the comparison.
 * This may also be an `Attestation`.
 * @tparam Right_ Integer type on the right hand side of the comparison.
 * This may also be an `Attestation`.
 *
 * @param left Non-negative value on the left hand side of the comparison.
 * @param right Non-negative value on the right hand side of the comparison.
 *
 * @return Whether `left` is less than or equal to `right`.
 */
template<typename Left_, typename Right_>
constexpr bool is_less_than_or_equal(Left_ left, Right_ right) {
    return as_unsigned(get_value(left)) <= as_unsigned(get_value(right));
}

/**
 * @tparam Left_ Integer type on the left hand side of the comparison.
 * This may also be an `Attestation`.
 * @tparam Right_ Integer type on the right hand side of the comparison.
 * This may also be an `Attestation`.
 *
 * @param left Non-negative value on the left hand side of the comparison.
 * @param right Non-negative value on the right hand side of the comparison.
 *
 * @return Whether `left` is greater than `right`.
 */
template<typename Left_, typename Right_>
constexpr bool is_greater_than(Left_ left, Right_ right) {
    return as_unsigned(get_value(left)) > as_unsigned(get_value(right));
}

/**
 * @tparam Left_ Integer type on the left hand side of the comparison.
 * This may also be an `Attestation`.
 * @tparam Right_ Integer type on the right hand side of the comparison.
 * This may also be an `Attestation`.
 *
 * @param left Non-negative value on the left hand side of the comparison.
 * @param right Non-negative value on the right hand side of the comparison.
 *
 * @return Whether `left` is greater than or equal to `right`.
 */
template<typename Left_, typename Right_>
constexpr bool is_greater_than_or_equal(Left_ left, Right_ right) {
    return as_unsigned(get_value(left)) >= as_unsigned(get_value(right));
}

/**
 * @tparam First_ First integer type. 
 * This may also be an `Attestation`.
 * @tparam Second_ Second integer type.
 * This may also be an `Attestation`.
 *
 * @param first First non-negative value. 
 * @param second Second non-negative value. 
 *
 * @return The smaller of `first` and `second`, in the smaller integer type of `First_` and `Second_`.
 */
template<typename First_, typename Second_>
constexpr auto min(First_ first, Second_ second) {
    const auto fval = get_value(first);
    const auto sval = get_value(second);
    const bool first_larger = as_unsigned(fval) > as_unsigned(sval);

    if constexpr(as_unsigned(get_max<First_>()) > as_unsigned(get_max<Second_>())) {
        if (first_larger) {
            return sval;
        } else {
            return static_cast<I<decltype(sval)> >(fval);
        }
    } else {
        if (first_larger) {
            return static_cast<I<decltype(fval)> >(sval);
        } else {
            return fval;
        }
    }
}

/**
 * @tparam First_ First integer type. 
 * This may also be an `Attestation`.
 * @tparam Second_ Second integer type.
 * This may also be an `Attestation`.
 *
 * @param first First non-negative value. 
 * @param second Second non-negative value. 
 *
 * @return The larger of `first` and `second`, in the larger integer type of `First_` and `Second_`.
 */
template<typename First_, typename Second_>
constexpr auto max(First_ first, Second_ second) {
    const auto fval = get_value(first);
    const auto sval = get_value(second);
    const bool first_larger = as_unsigned(fval) > as_unsigned(sval);

    if constexpr(as_unsigned(get_max<First_>()) > as_unsigned(get_max<Second_>())) {
        if (first_larger) {
            return fval;
        } else {
            return static_cast<I<decltype(fval)> >(sval);
        }
    } else {
        if (first_larger) {
            return static_cast<I<decltype(sval)> >(fval);
        } else {
            return sval;
        }
    }
}

}

#endif
