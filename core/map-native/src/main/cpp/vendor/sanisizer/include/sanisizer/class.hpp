#ifndef SANISIZER_CLASS_HPP
#define SANISIZER_CLASS_HPP

#include <type_traits>

#include "cast.hpp"
#include "attest.hpp"

/**
 * @file class.hpp
 * @brief Wrapper classes for integer casting.
 */

namespace sanisizer {

/**
 * @brief Cast an integer in a function call.
 *
 * @tparam Value_ Type of the integer to be casted from.
 * This may also be an `Attestation`.
 *
 * Consider a function `f(T x)` for some (non-template) integer `T`.
 * When calling this function on an integer `y`, we can use `f(Cast(y))`, 
 * which will automatically cast `y` to `T` via `cast()`.
 */
template<typename Value_>
class Cast {
    static_assert(is_integral_or_Attestation<Value_>::value);
    Value_ my_x;

public:
    /**
     * @param x Integer value.
     */
    constexpr Cast(Value_ x) : my_x(x) {}

    /**
     * @tparam Output_ Type of the integer to be casted to.
     * @return `x` as an `Output_`, or an error if the cast fails.
     */
    template<typename Output_>
    constexpr operator Output_() const {
        return cast<Output_>(my_x);
    }
};

/**
 * @brief Do not cast an integer in a function call.
 *
 * @tparam Integer_ Type of the integer used in the call.
 *
 * Consider a function `f(T x)` for some (non-template) integer `T`.
 * When calling this function on an integer `y`, we can use `f(Exact(y))`, 
 * which will throw a compile-time error if the type of `y` is not `T`. 
 */
template<typename Integer_>
class Exact {
    static_assert(std::is_integral<Integer_>::value);
    Integer_ my_x;

public:
    /**
     * @param x Integer value.
     */
    constexpr Exact(Integer_ x) : my_x(x) {}

    /**
     * @return `x` as an `Integer_`.
     */
    constexpr operator Integer_() const {
        return my_x;
    }

    /**
     * @tparam Output_ Type of the integer to be casted to.
     *
     * This is deleted so any attempt at a conversion will result in a compile-time error.
     */
    template<typename Output_>
    constexpr operator Output_() const = delete;
};

}

#endif
