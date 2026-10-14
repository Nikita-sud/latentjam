#ifndef SANISIZER_ATTEST_HPP
#define SANISIZER_ATTEST_HPP

#include <limits>
#include <type_traits>
#include <cassert>
#include <stdexcept>

#include "utils.hpp"

/**
 * @file attest.hpp
 * @brief Create compile-time attestations.
 */

namespace sanisizer {

/**
 * @brief Attest to additional compile-time properties of an integer.
 *
 * @tparam Integer_ Type of the integer.
 * @tparam max_ Maximum value of the integer, known at compile time.
 * This should be non-negative.
 *
 * `max_` is a compile-time attestations to the properties of the integer.
 * This allows developers to specify more constraints on the integer than would otherwise be provided by `Integer_`.
 * For example, we could attest that a `std::size_t` has a upper limit of `max_ = 2^31 - 1`.
 */
template<typename Integer_, Integer_ max_>
struct Attestation {
    static_assert(std::is_integral<Integer_>::value);
    static_assert(max_ >= 0);

    /**
     * @param x Value of the integer.
     * This should be no greater than `max_`.
     */
    constexpr Attestation(Integer_ x) : value(x) {
        assert(x <= max_);
    }

    /**
     * Type of the integer.
     */
    typedef Integer_ Integer;

    /**
     * Maximum value of the integer, known at compile time.
     * This will be non-negative.
     */
    static constexpr Integer_ max = max_;

    /**
     * Value of the integer.
     */
    Integer_ value;
};

/**
 * Default case for checking whether `Value_` is an `Attestation`.
 *
 * @tparam Value_ Type to be queried.
 */
template<typename Value_>
struct is_Attestation {
    /**
     * Fale, `Value_` is not an `Attestation`.
     */
    static constexpr bool value = false;
};

/**
 * Positive case for checking whether a class instance is an `Attestation`.
 *
 * @tparam Integer_ See documentation for `Attestation`.
 * @tparam max_ See documentation for `Attestation`.
 */
template<typename Integer_, Integer_ max_>
struct is_Attestation<Attestation<Integer_, max_> > {
    /**
     * True, this class is an `Attestation`.
     */
    static constexpr bool value = true;
};

/**
 * Whether `Value_` is an integer or `Attestation`.
 *
 * @tparam Value_ Type to be queried.
 */
template<typename Value_>
struct is_integral_or_Attestation {
    /**
     * Whether `Value_` is an integer or `Attestation`.
     */
    static constexpr bool value = std::is_integral<Value_>::value || is_Attestation<Value_>::value;
};

/**
 * @tparam Value_ Integer or `Attestation`.
 * @param x Integer value or an `Attestation` about an integer.
 * @return `x` if it is already an integer, otherwise `Attestation::value`.
 */
template<typename Value_>
constexpr auto get_value(Value_ x) {
    if constexpr(std::is_integral<Value_>::value) {
        return x;
    } else {
        static_assert(is_Attestation<Value_>::value);
        return x.value;
    }
}

/**
 * @tparam Value_ Integer or `Attestation`.
 * @return The maximum value of all instances of `Value_`.
 */
template<typename Value_>
constexpr auto get_max() {
    if constexpr(std::is_integral<Value_>::value) {
        return std::numeric_limits<Value_>::max();
    } else {
        static_assert(is_Attestation<Value_>::value);
        return Value_::max;
    }
}

/**
 * @tparam Max_ Integer type of the new compile-time maximum.
 * @tparam new_max_ The new compile-time maximum.
 * @tparam Value_ Integer or `Attestation`.
 * @param x Integer value or an `Attestation` about an integer.
 * @return `x` if it is already known to be less than or equal to `new_max_`, 
 * otherwise, an `Attestation` that attests to this constraint.
 */
template<typename Max_, Max_ new_max_, typename Value_>
constexpr auto attest_max(Value_ x) {
    constexpr auto unsigned_new_limit = static_cast<typename std::make_unsigned<Max_>::type>(new_max_);
    if constexpr(std::is_integral<Value_>::value) {
        constexpr auto max_value = static_cast<typename std::make_unsigned<Value_>::type>(std::numeric_limits<Value_>::max());
        if constexpr(max_value <= unsigned_new_limit) {
            return x; 
        } else {
            return Attestation<Value_, static_cast<Value_>(new_max_)>(x);
        }
    } else {
        static_assert(is_Attestation<Value_>::value);
        typedef typename Value_::Integer WrappedInteger;
        constexpr auto max_value = static_cast<typename std::make_unsigned<WrappedInteger>::type>(Value_::max);
        if constexpr(max_value <= unsigned_new_limit) {
            return x;
        } else {
            return Attestation<WrappedInteger, static_cast<WrappedInteger>(new_max_)>(x.value);
        }
    }
}

/**
 * @tparam Max_ Integer type of the new compile-time maximum.
 * @tparam Value_ Integer or `Attestation`.
 * @param x Integer value or an `Attestation` about an integer.
 * @return `x` if it is already known to be less than or equal to the maximum value of `Max_`;
 * otherwise, an `Attestation` that attests to this constraint.
 */
template<typename Max_, typename Value_>
constexpr auto attest_max_by_type(Value_ x) {
    return attest_max<Max_, std::numeric_limits<Max_>::max()>(x);
}

/**
 * @tparam Dest_ Integer type of the destination.
 * @tparam Value_ Integer or `Attestation`.
 * @param x Integer value or an `Attestation` about an integer.
 * @return An overflow error is thrown if `x`'s value would overflow when stored in `Dest_`.
 * Otherwise, `false` is returned.
 */
template<typename Dest_, typename Value_>
constexpr bool check_overflow(Value_ x) {
    static_assert(std::is_integral<Dest_>::value);
    constexpr auto umaxed = as_unsigned(std::numeric_limits<Dest_>::max());
    static_assert(is_integral_or_Attestation<Value_>::value);
    if constexpr(umaxed < as_unsigned(get_max<Value_>())) {
        if (umaxed < as_unsigned(get_value(x))) {
            throw std::overflow_error("overflow detected when casting size-like values in sanisizer");
        }
    }
    return false;
}

/** 
 * @cond
 */
template<typename Value_>
Value_ attest_gez(Value_ x) { return x; }
/** 
 * @endcond
 */

}

#endif
