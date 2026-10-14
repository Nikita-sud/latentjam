#ifndef SANISIZER_ARITHMETIC_HPP
#define SANISIZER_ARITHMETIC_HPP

#include <limits>
#include <type_traits>
#include <stdexcept>

#include "attest.hpp"
#include "utils.hpp"

/**
 * @file arithmetic.hpp
 * @brief Safe arithmetic on integer sizes.
 */

namespace sanisizer {

/**
 * @cond
 */
template<typename Dest_, typename First_, typename Second_>
constexpr bool needs_sum_check() {
    constexpr Dest_ dest_maxed = std::numeric_limits<Dest_>::max();
    constexpr auto first_maxed = get_max<First_>();
    if constexpr(as_unsigned(first_maxed) > as_unsigned(dest_maxed)) {
        return true;
    } else {
        constexpr Dest_ delta = dest_maxed - static_cast<Dest_>(first_maxed);
        constexpr auto second_maxed = get_max<Second_>();
        return as_unsigned(second_maxed) > as_unsigned(delta);
    }
}

template<typename Dest_, typename First_, typename Second_>
constexpr auto sum_protected(First_ first, Second_ second) {
    check_overflow<Dest_>(first);
    const Dest_ first_val = get_value(first);

    check_overflow<Dest_>(second);
    const Dest_ second_val = get_value(second);

    if constexpr(needs_sum_check<Dest_, First_, Second_>()) {
        static_assert(std::is_integral<Dest_>::value);
        constexpr Dest_ dest_maxed = std::numeric_limits<Dest_>::max();
        if (static_cast<Dest_>(dest_maxed - first_val) < second_val) {
            throw std::overflow_error("overflow detected in sanisizer::sum");
        }
        return Attestation<Dest_, dest_maxed>(static_cast<Dest_>(first_val + second_val));

    } else {
        constexpr Dest_ maxsum = static_cast<Dest_>(get_max<First_>()) + static_cast<Dest_>(get_max<Second_>());
        return Attestation<Dest_, maxsum>(static_cast<Dest_>(first_val + second_val));
    }
}

template<typename Dest_, typename First_, typename Second_, typename ... Args_>
constexpr auto sum_protected(First_ first, Second_ second, Args_... more) {
    const auto subsum = sum_protected<Dest_>(first, second); 
    return sum_protected<Dest_>(subsum, more...);
}

template<typename Dest_, typename First_, typename Second_>
constexpr Dest_ sum_unprotected(First_ first, Second_ second) {
    static_assert(std::is_integral<Dest_>::value);
    static_assert(std::is_integral<First_>::value);
    static_assert(std::is_integral<Second_>::value);
    return static_cast<Dest_>(first) + static_cast<Dest_>(second);
}

template<typename Dest_, typename First_, typename Second_, typename ... Args_>
constexpr Dest_ sum_unprotected(First_ first, Second_ second, Args_... more) {
    const auto subsum = sum_unprotected<Dest_>(first, second);
    return sum_unprotected<Dest_>(subsum, more...);
}
/**
 * @endcond
 */

/**
 * Add two or more non-negative values, checking for overflow in the destination type.
 * This is typically used to compute the size of an array that is a concatenation of smaller arrays.
 *
 * @tparam Dest_ Integer type of the destination.
 * @tparam First_ Integer type of the first value.
 * This may also be an `Attestation`.
 * @tparam Args_ Integer types of additional values.
 * Any number of these may also be `Attestation`s.
 *
 * @param first First non-negative value to add.
 * @param more Additional non-negative values to add.
 *
 * @return Sum of all arguments as a `Dest_`.
 * An error is raised if an overflow would occur.
 */
template<typename Dest_, typename First_, typename ... Args_>
constexpr Dest_ sum(First_ first, Args_... more) {
    return get_value(sum_protected<Dest_>(first, more...));
}

/**
 * Unsafe version of `sum()` that casts its arguments to `Dest_` before summation but does not check for overflow.
 * This is more efficent if it is already known that the sum will not overflow, e.g., from previous calls to `sum()` with larger values.
 *
 * @tparam Dest_ Integer type of the destination.
 * @tparam First_ Integer type of the first value.
 * @tparam Args_ Integer types of additional values.
 * 
 * @param first Non-negative value to add.
 * @param more Additional non-negative values to add.
 *
 * @return Sum of all arguments as a `Dest_`.
 */
template<typename Dest_, typename First_, typename ... Args_>
constexpr Dest_ sum_unsafe(First_ first, Args_... more) {
    return sum_unprotected<Dest_>(first, more...);
}

/**
 * @cond
 */
template<typename Dest_, typename First_, typename Second_>
constexpr bool needs_product_check() {
    constexpr Dest_ dest_maxed = std::numeric_limits<Dest_>::max();
    constexpr auto first_maxed = get_max<First_>();
    if constexpr(as_unsigned(first_maxed) > as_unsigned(dest_maxed)) {
        return true;
    } else if constexpr(first_maxed == 0) {
        return false;
    } else {
        constexpr auto ratio = dest_maxed / first_maxed;
        constexpr auto second_maxed = get_max<Second_>();
        return as_unsigned(second_maxed) > as_unsigned(ratio);
    }
}

template<typename Dest_, typename First_, typename Second_>
constexpr auto product_protected(First_ first, Second_ second) {
    check_overflow<Dest_>(first);
    const Dest_ first_val = get_value(first);

    check_overflow<Dest_>(second);
    const Dest_ second_val = get_value(second);

    if constexpr(needs_product_check<Dest_, First_, Second_>()) {
        static_assert(std::is_integral<Dest_>::value);
        constexpr Dest_ dest_maxed = std::numeric_limits<Dest_>::max();
        if (first_val && static_cast<Dest_>(dest_maxed / first_val) < second_val) {
            throw std::overflow_error("overflow detected in sanisizer::product");
        }
        return Attestation<Dest_, dest_maxed>(static_cast<Dest_>(first_val * second_val));

    } else {
        constexpr Dest_ maxprod = static_cast<Dest_>(get_max<First_>()) * static_cast<Dest_>(get_max<Second_>());
        return Attestation<Dest_, maxprod>(static_cast<Dest_>(first_val * second_val));
    }
}

template<typename Dest_, typename First_, typename Second_, typename ... Args_>
constexpr auto product_protected(First_ first, Second_ second, Args_... more) {
    const auto subproduct = product_protected<Dest_>(first, second);
    return product_protected<Dest_>(subproduct, more...);
}

template<typename Dest_, typename First_, typename Second_>
constexpr Dest_ product_unprotected(First_ left, Second_ right) {
    static_assert(std::is_integral<Dest_>::value);
    static_assert(std::is_integral<First_>::value);
    static_assert(std::is_integral<Second_>::value);
    return static_cast<Dest_>(left) * static_cast<Dest_>(right);
}

template<typename Dest_, typename First_, typename Second_, typename ... Args_>
constexpr Dest_ product_unprotected(First_ left, Second_ right, Args_... more) {
    const auto subproduct = product_unprotected<Dest_>(left, right);
    return product_unprotected<Dest_>(subproduct, more...);
}
/**
 * @endcond
 */

/**
 * Multiply two or more non-negative values, checking for overflow in the destination type.
 * This is typically used to compute the size of a flattened N-dimensional array as the product of its dimension extents.
 * 
 * For consistency, this function will also check that each input value can be cast to `Dest_`.
 * This ensures that per-dimension indices/extents can be safely represented as `Dest_` in later steps (e.g., `nd_offset()`). 
 * These checks are necessary as the product may fit in `Dest_` but not the input values if one of the inputs is zero. 
 *
 * @tparam Dest_ Integer type of the destination.
 * @tparam First_ Integer type of the first value.
 * This may also be an `Attestation`.
 * @tparam Args_ Integer types of additional values.
 * Any number of these may also be `Attestation`s.
 *
 * @param first Non-negative value to multiply.
 * @param more Additional non-negative values to multiply.
 *
 * @return Product of all arguments as a `Dest_`.
 * An error is raised if an overflow would occur.
 */
template<typename Dest_, typename First_, typename ... Args_>
constexpr Dest_ product(First_ first, Args_... more) {
    return get_value(product_protected<Dest_>(first, more...));
}

/**
 * Unsafe version of `product()` that casts its arguments to `Dest_` but does not check for overflow.
 * This is more efficent if it is known that the product will not overflow, e.g., from previous calls to `product()` with larger values.
 *
 * @tparam Dest_ Integer type of the destination.
 * @tparam Args_ Integer types of additional values.
 * 
 * @param first Value to multiply.
 * @param more Additional values to multiply.
 *
 * @return Product of all arguments as a `Dest_`.
 */
template<typename Dest_, typename First_, typename ... Args_>
constexpr Dest_ product_unsafe(First_ first, Args_... more) {
    return product_unprotected<Dest_>(first, more...);
}

}

#endif
