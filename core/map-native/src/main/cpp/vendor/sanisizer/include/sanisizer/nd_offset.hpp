#ifndef SANISIZER_ND_OFFSET_HPP
#define SANISIZER_ND_OFFSET_HPP

#include <type_traits>

/**
 * @file nd_offset.hpp
 * @brief Offsets for accessing N-dimensional arrays.
 */

namespace sanisizer {

/**
 * @cond
 */
template<typename Size_>
constexpr Size_ nd_offset_internal(Size_ extent, Size_ pos) {
    static_assert(std::is_integral<Size_>::value);
    return extent * pos;
}

template<typename Size_, typename... MoreArgs_>
constexpr Size_ nd_offset_internal(Size_ extent, Size_ pos, MoreArgs_... more_args) {
    static_assert(std::is_integral<Size_>::value);
    return (pos + nd_offset_internal<Size_>(more_args...)) * extent;
}
/**
 * @endcond
 */

/**
 * Compute offsets for accessing elements in a flattened N-dimensional array (for N > 1).
 * The first dimension is assumed to be the fastest-changing, followed by the second dimension, and so on.
 * 
 * @tparam Size_ Integer type to represent the size of the flattened array.
 * @tparam FirstIndex_ Integer type to represent the index on the first dimension.
 * It is assumed that this can be safely cast to `Size_`, as overflow checks should have been performed during array allocation, e.g., via `product()`.
 * @tparam FirstExtent_ Integer type to represent the extent of the first dimension.
 * It is assumed that this can be safely cast to `Size_`, as overflow checks should have been performed during array allocation, e.g., via `product()`.
 * @tparam SecondIndex_ Integer type to represent the index on the second dimension.
 * It is assumed that this can be safely cast to `Size_`, as overflow checks should have been performed during array allocation, e.g., via `product()`.
 * @tparam Remaining_ Additional arguments for further dimensions.
 * It is assumed that all types can be safely cast to `Size_`, as overflow checks should have been performed during array allocation, e.g., `product()`.
 *
 * @param x1 Position on the first dimension.
 * @param extent1 Extent of the first dimension.
 * @param x2 Position on the second dimension.
 * @param remaining Additional arguments for further dimensions.
 * These should be `(extentP, xQ)` pairs where `extentP` is the extent of the `P`-th dimension and `xQ` is the position on the `Q = P + 1`-th dimension.
 * For example, for a 3-dimensional array, we would expect an `extent2` and `x3` argument.
 *
 * @return Offset into the array for element `(x1, x2, ...)`.
 */
template<typename Size_, typename FirstIndex_, typename FirstExtent_, typename SecondIndex_, typename... Remaining_>
constexpr Size_ nd_offset(FirstIndex_ x1, FirstExtent_ extent1, SecondIndex_ x2, Remaining_... remaining) {
    static_assert(std::is_integral<Size_>::value);
    static_assert(std::is_integral<FirstIndex_>::value);
    static_assert(std::is_integral<FirstExtent_>::value);
    static_assert(std::is_integral<SecondIndex_>::value);

    // Note that we don't use Size_ in the function signature, even though everything is cast to Size_.
    // This avoids inadvertent deduction of Size_ from the input types.
    // The user is always forced to explicitly specify Size_ to avoid any risk of accidental overflow.
    return static_cast<Size_>(x1) + nd_offset_internal<Size_>(extent1, x2, remaining...);
}

}

#endif

